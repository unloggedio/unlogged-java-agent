package com.videobug.agent.logging.perthread;

import com.insidious.common.UploadFile;
import com.insidious.common.cqengine.ObjectInfoDocument;
import com.insidious.common.cqengine.StringInfoDocument;
import com.insidious.common.cqengine.TypeInfoDocument;
import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.logging.util.NetworkClient;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

public class RawFileCollector implements Runnable {
    public static final int MAX_CONSECUTIVE_FAILURE_COUNT = 10;
    public static final int FAILURE_SLEEP_DELAY = 10;
    public static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(1);
    private final IErrorLogger errorLogger;
    private final BlockingQueue<UploadFile> fileList;
    private final FileNameGenerator indexFileNameGenerator;
    private final List<byte[]> classWeaves = new LinkedList<>();
    private final List<TypeInfoDocument> typeInfoDocuments;
    private final NetworkClient networkClient;
    public int filesPerArchive = 0;
    private boolean shutdown = false;
    private boolean skipUploads;
    private ArchivedIndexWriter archivedIndexWriter;
    private int fileCount = 0;
    private BlockingQueue<StringInfoDocument> stringsToIndex;
    private BlockingQueue<TypeInfoDocument> typesToIndex;
    private BlockingQueue<ObjectInfoDocument> objectsToIndex;

    public RawFileCollector(int filesPerArchive,
                            FileNameGenerator indexFileNameGenerator,
                            NetworkClient networkClient, IErrorLogger errorLogger
    ) throws IOException {
        this.filesPerArchive = filesPerArchive;
        this.networkClient = networkClient;
        this.indexFileNameGenerator = indexFileNameGenerator;
        this.errorLogger = errorLogger;
        this.fileList = new ArrayBlockingQueue<>(1024 * 128);
        this.typeInfoDocuments = new LinkedList<>();
        prepareIndexItemBuffers();
        prepareArchive();

    }

    private void prepareArchive() throws IOException {

        ArchivedIndexWriter archivedIndexWriterOld = archivedIndexWriter;

        archivedIndexWriter = new ArchivedIndexWriter(indexFileNameGenerator.getNextFile(), this.classWeaves, errorLogger);
        fileCount = 0;
        if (archivedIndexWriterOld != null) {
            EXECUTOR_SERVICE.submit(() -> {
                drainItemsToIndex(archivedIndexWriterOld);
                archivedIndexWriterOld.drainQueueToIndex(List.of(), typeInfoDocuments, List.of());
                archivedIndexWriterOld.close();
                if (networkClient != null) {
                    File archiveFile = archivedIndexWriterOld.getArchiveFile();
                    try {
                        errorLogger.log("uploading file: " + archiveFile.getAbsolutePath());
                        networkClient.uploadFile(archiveFile.getAbsolutePath());
                    } catch (IOException e) {
                        errorLogger.log("failed to upload archive file: " + e.getMessage());
                        archiveFile.delete();
                    }
                }
            });
        }
    }

    public void shutdown() throws IOException {
        shutdown = true;
        EXECUTOR_SERVICE.shutdownNow().forEach(Runnable::run);
        upload();
    }

    public void upload() throws IOException {
        try {
            UploadFile logFile = fileList.poll(1, TimeUnit.SECONDS);
            if (logFile == null) {
                if (fileCount > 0) {
                    errorLogger.log("files from queue, currently [" + fileCount + "] files in list");
                    prepareArchive();
                }
                return;
            }

            List<UploadFile> logFiles = new LinkedList<>();
            fileList.drainTo(logFiles, filesPerArchive - archivedIndexWriter.fileCount());
            logFiles.add(logFile);

            errorLogger.log("add [" + logFiles.size() + "] files");
            for (UploadFile file : logFiles) {
                File fileToUpload = new File(file.path);
                archivedIndexWriter.writeFileEntry(file);
                fileCount++;
                fileToUpload.delete();
            }
        } catch (IOException e) {
            System.err.println("Failed to upload file: " + e.getMessage());
            errorLogger.log(e);
        } catch (InterruptedException e) {
            errorLogger.log("file upload cron interrupted, shutting down");
        } finally {
            if (archivedIndexWriter.fileCount() >= filesPerArchive) {
                prepareArchive();
            }
        }
    }

    public void drainItemsToIndex(IndexOutputStream writer) {

        List<ObjectInfoDocument> objectInfoDocuments = new LinkedList<>();
        List<StringInfoDocument> stringInfoDocuments = new LinkedList<>();

        objectsToIndex.drainTo(objectInfoDocuments);
        typesToIndex.drainTo(typeInfoDocuments);
        stringsToIndex.drainTo(stringInfoDocuments);

        if (objectInfoDocuments.size() == 0 && stringInfoDocuments.size() == 0 && typeInfoDocuments.size() == 0) {
            return;
        }

        prepareIndexItemBuffers();
        writer.drainQueueToIndex(objectInfoDocuments, List.of(), stringInfoDocuments);


    }

    void prepareIndexItemBuffers() {
        objectsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        stringsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        typesToIndex = new ArrayBlockingQueue<>(1024 * 1024);
    }

    @Override
    public void run() {
        while (true) {
            // errorLogger.log("add file");
            long start = System.currentTimeMillis();
            if (shutdown) {
                return;
            }
            try {
                new Thread(() -> drainItemsToIndex(archivedIndexWriter)).start();
                upload();
            } catch (IOException e) {
                errorLogger.log(e);
            }
            long timeToProcessFile = System.currentTimeMillis() - start;
//            errorLogger.log("adding file took [" + timeToProcessFile + "] ms");
        }
    }

    public void indexObjectTypeEntry(long id, int typeId) {
        objectsToIndex.offer(new ObjectInfoDocument(id, typeId));
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

    public void indexTypeEntry(int typeId, String typeName) {
        typesToIndex.offer(new TypeInfoDocument(typeId, typeName));
    }

    public void addClassWeaveInfo(byte[] byteArray) {
        classWeaves.add(byteArray);
    }

    public BlockingQueue<UploadFile> getFileQueue() {
        return this.fileList;
    }
}