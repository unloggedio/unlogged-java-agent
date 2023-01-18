package com.videobug.agent.logging.perthread;

import com.insidious.common.UploadFile;
import com.insidious.common.cqengine.ObjectInfoDocument;
import com.insidious.common.cqengine.StringInfoDocument;
import com.insidious.common.cqengine.TypeInfoDocument;
import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.logging.util.NetworkClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RawFileCollector implements Runnable {
    public static final int MAX_CONSECUTIVE_FAILURE_COUNT = 10;
    public static final int FAILURE_SLEEP_DELAY = 10;
    public static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(4);
    private final IErrorLogger errorLogger;
    private final BlockingQueue<UploadFile> fileList;
    private final FileNameGenerator indexFileNameGenerator;
    //    private final List<byte[]> classWeaves = new LinkedList<>();
    private final List<TypeInfoDocument> typeInfoDocuments;
    private final NetworkClient networkClient;
    private final BlockingQueue<TypeInfoDocument> typesToIndex;
    private final LinkedList<ObjectInfoDocument> EMPTY_LIST = new LinkedList<>();
    private final LinkedList<StringInfoDocument> EMPTY_STRING_LIST = new LinkedList<>();
    private final LinkedList<TypeInfoDocument> EMPTY_TYPE_LIST = new LinkedList<>();
    private final FileOutputStream classWeaveFileRaw;
    private final File outputDir;
    private final List<ObjectInfoDocument> objectInfoDocuments = new ArrayList<>();
    public int filesPerArchive = 0;
    private boolean shutdown = false;
    private boolean shutdownComplete = false;
    private boolean skipUploads;
    private ArchivedIndexWriter archivedIndexWriter;
    private int fileCount = 0;
    private BlockingQueue<StringInfoDocument> stringsToIndex;
    private BlockingQueue<ObjectInfoDocument> objectsToIndex;
    private AtomicBoolean isDraining = new AtomicBoolean(false);

    public RawFileCollector(int filesPerArchive,
                            FileNameGenerator indexFileNameGenerator,
                            NetworkClient networkClient,
                            IErrorLogger errorLogger,
                            File outputDir) throws IOException {
        this.filesPerArchive = filesPerArchive;
        this.networkClient = networkClient;
        this.indexFileNameGenerator = indexFileNameGenerator;
        this.errorLogger = errorLogger;
        this.fileList = new ArrayBlockingQueue<>(1024 * 128);
        this.typeInfoDocuments = new LinkedList<>();
        typesToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        objectsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        stringsToIndex = new ArrayBlockingQueue<>(1024 * 1024);

        this.outputDir = outputDir;
        errorLogger.log("Created raw file collector, files per archive: " + filesPerArchive);
        finalizeArchiveAndUpload();
        classWeaveFileRaw = new FileOutputStream(new File(outputDir + "/" + "class.weave.dat"));

    }

    private void finalizeArchiveAndUpload() throws IOException {

        ArchivedIndexWriter archivedIndexWriterOld = archivedIndexWriter;

        archivedIndexWriter = new ArchivedIndexWriter(indexFileNameGenerator.getNextFile(),
                outputDir + "/class.weave.dat", errorLogger);
        fileCount = 0;
        if (archivedIndexWriterOld != null) {
            EXECUTOR_SERVICE.submit(() -> {
                try {
                    errorLogger.log("closing archive: " + archivedIndexWriterOld.getArchiveFile()
                            .getName());
                    drainItemsToIndex(archivedIndexWriterOld);
                    archivedIndexWriterOld.drainQueueToIndex(EMPTY_LIST, typeInfoDocuments, EMPTY_STRING_LIST);
                    archivedIndexWriterOld.close();
                    errorLogger.log("closed archive: " + archivedIndexWriterOld.getArchiveFile()
                            .getName());
                } catch (Throwable e) {
                    e.printStackTrace();
                }

                if (networkClient != null && !"localhost-token".equals(networkClient.getToken())) {
                    File archiveFile = archivedIndexWriterOld.getArchiveFile();
                    try {
                        errorLogger.log("uploading file: " + archiveFile.getAbsolutePath());
                        networkClient.uploadFile(archiveFile.getAbsolutePath());
                    } catch (IOException e) {
                        errorLogger.log("failed to upload archive file: " + e.getMessage());
                    } finally {
                        archiveFile.delete();
                    }
                }
            });
        }
    }

    public void shutdown() throws IOException {
        shutdown = true;
        errorLogger.log("shutting down raw file collector");
        EXECUTOR_SERVICE.shutdown();
    }

    public void upload() throws IOException {
        if (shutdownComplete) {
            return;
        }
        boolean doneFinalize = false;
        try {
            UploadFile logFile = fileList.poll(1, TimeUnit.SECONDS);
            if (logFile == null) {
                if (fileCount > 0 || shutdown) {
                    errorLogger.log(
                            "files from queue, currently [" + fileCount + "] files in list : shutdown: " + shutdown);
                    doneFinalize = true;
                    finalizeArchiveAndUpload();
                    return;
                }
                errorLogger.log("nothing to load: " + shutdown);
                return;
            }

            List<UploadFile> logFiles = new LinkedList<>();
            fileList.drainTo(logFiles, filesPerArchive - archivedIndexWriter.fileCount());
            logFiles.add(logFile);

            errorLogger.log("add [" + logFiles.size() + "] files");
            for (UploadFile file : logFiles) {
                File fileToAddToArchive = new File(file.path);
                archivedIndexWriter.writeFileEntry(file);
                fileCount++;
                fileToAddToArchive.delete();
            }
        } catch (IOException e) {
            System.err.println("[videobug] failed to upload file: " + e.getMessage());
            errorLogger.log(e);
        } catch (InterruptedException e) {
            errorLogger.log("file upload cron interrupted, shutting down");
        } finally {
            errorLogger.log("finally check can archive [" + archivedIndexWriter.getArchiveFile()
                    .getName() + "] can " +
                    "be closed: " + archivedIndexWriter.fileCount() + " >= " + filesPerArchive);
            if (archivedIndexWriter.fileCount() >= filesPerArchive || shutdown) {
                if (!doneFinalize) {
                    finalizeArchiveAndUpload();
                }
            }
            if (shutdown) {
                shutdownComplete = true;
            }
        }
    }

    public void drainItemsToIndex(IndexOutputStream writer) {
        if (isDraining.compareAndSet(false, true)) {
            return;
        }
        errorLogger.log("Drain items to index: ");
        List<StringInfoDocument> stringInfoDocuments = new ArrayList<>();


        objectsToIndex.drainTo(objectInfoDocuments);

        List<TypeInfoDocument> newTypes = new ArrayList<>();
        typesToIndex.drainTo(newTypes);

        typeInfoDocuments.addAll(newTypes);

        stringsToIndex.drainTo(stringInfoDocuments);

        if (objectInfoDocuments.size() == 0 && stringInfoDocuments.size() == 0 && typeInfoDocuments.size() == 0) {
            errorLogger.log("no new data to record, return");
            return;
        }
        writer.drainQueueToIndex(objectInfoDocuments, EMPTY_TYPE_LIST, stringInfoDocuments);

        isDraining.set(false);

    }

    void prepareIndexItemBuffers() {
        objectsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        stringsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
//        typesToIndex = new ArrayBlockingQueue<>(1024 * 1024);
    }

    @Override
    public void run() {
        try {
            while (true) {
                long start = System.currentTimeMillis();
                errorLogger.log(start + " : run raw file collector cron: " + shutdown);
                if (shutdown) {
                    return;
                }
                try {
                    EXECUTOR_SERVICE.submit(() -> drainItemsToIndex(archivedIndexWriter));
                    upload();
                } catch (IOException e) {
                    errorLogger.log(e);
                }
                Thread.sleep(1000);
//                long timeToProcessFile = System.currentTimeMillis() - start;
//            errorLogger.log("adding file took [" + timeToProcessFile + "] ms");
            }
        } catch (Throwable e) {
            errorLogger.log("failed to write archived index to disk: " + e.getMessage());
        }
    }

    public void indexObjectTypeEntry(long id, int typeId) {
        objectsToIndex.offer(new ObjectInfoDocument(id, typeId));
//        System.err.println("Offering object [" + id + "] of type [" + typeId + "] => " + objectsToIndex.size());

    }

    public void indexStringEntry(long id, String stringObject) {
        stringsToIndex.offer(new StringInfoDocument(id, stringObject));

    }

    public void addValueId(long valueId) {
        archivedIndexWriter.addValueId(valueId);

    }

    public void addProbeId(int probeId) {
        archivedIndexWriter.addProbeId(probeId);
    }

    public void indexTypeEntry(int typeId, String typeName, byte[] typeInfoBytes) {
//        System.err.println("Offering type [" + typeId + "] -> " + typeName + ". Now collected " + typesToIndex.size());
        typesToIndex.offer(new TypeInfoDocument(typeId, typeName, typeInfoBytes));
    }

    synchronized public void addClassWeaveInfo(byte[] byteArray) {
        try {
            classWeaveFileRaw.write(byteArray);
        } catch (IOException e) {
            errorLogger.log("Failed to write class weave information: " + e.getMessage());
        }
    }

    public BlockingQueue<UploadFile> getFileQueue() {
        return this.fileList;
    }
}