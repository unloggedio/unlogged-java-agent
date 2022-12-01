package com.videobug.agent.logging.perthread;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.radixinverted.InvertedRadixTreeIndex;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.insidious.common.BloomFilterUtil;
import com.insidious.common.UploadFile;
import com.insidious.common.cqengine.ObjectInfoDocument;
import com.insidious.common.cqengine.StringInfoDocument;
import com.insidious.common.cqengine.TypeInfoDocument;
import com.videobug.agent.logging.IErrorLogger;
import orestes.bloomfilter.BloomFilter;
import orestes.bloomfilter.json.BloomFilterConverter;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchivedIndexWriter implements IndexOutputStream {

    public static final String WEAVE_DAT_FILE = "class.weave.dat";
    public static final String INDEX_TYPE_DAT_FILE = "index.type.dat";
    public static final String INDEX_STRING_DAT_FILE = "index.string.dat";
    public static final String INDEX_OBJECT_DAT_FILE = "index.object.dat";
    public static final String INDEX_EVENTS_DAT_FILE = "index.events.dat";

    private final IErrorLogger errorLogger;
    private final Lock indexWriterLock = new ReentrantLock();
    private final String outputDir;
    private final File currentArchiveFile;
    private final List<byte[]> classWeaves;
    final private BloomFilter<Long> aggregatedValueSet;
    final private BloomFilter<Integer> aggregatedProbeIdSet;
    private BlockingQueue<StringInfoDocument> stringsToIndex;
    private BlockingQueue<TypeInfoDocument> typesToIndex;
    private BlockingQueue<ObjectInfoDocument> objectsToIndex;
    private ConcurrentIndexedCollection<TypeInfoDocument> typeInfoIndex;
    private ConcurrentIndexedCollection<StringInfoDocument> stringInfoIndex;
    private ConcurrentIndexedCollection<ObjectInfoDocument> objectInfoIndex;
    private DiskPersistence<ObjectInfoDocument, Long> objectInfoDocumentIntegerDiskPersistence;
    private DiskPersistence<StringInfoDocument, Long> stringInfoDocumentStringDiskPersistence;
    private DiskPersistence<TypeInfoDocument, Integer> typeInfoDocumentStringDiskPersistence;
    private List<UploadFile> fileListToUpload = new LinkedList<>();
    private ZipOutputStream archivedIndexOutputStream;

    public ArchivedIndexWriter(File archiveFile, List<byte[]> classWeaves, IErrorLogger errorLogger) throws IOException {
        this.errorLogger = errorLogger;
        this.classWeaves = classWeaves;
        outputDir = archiveFile.getParent() + "/";
        this.currentArchiveFile = archiveFile;

        initIndexQueues();

        errorLogger.log("prepare index archive: " + currentArchiveFile.getAbsolutePath());
        archivedIndexOutputStream = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(currentArchiveFile)));
        aggregatedValueSet = BloomFilterUtil.newBloomFilterForValues(BloomFilterUtil.BLOOM_AGGREGATED_FILTER_BIT_SIZE);
        aggregatedProbeIdSet = BloomFilterUtil.newBloomFilterForProbes(
                BloomFilterUtil.BLOOM_AGGREGATED_FILTER_BIT_SIZE);


        initialiseIndexes();

    }

    public File getArchiveFile() {
        return currentArchiveFile;
    }

    private void initIndexQueues() {
        typesToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        objectsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
        stringsToIndex = new ArrayBlockingQueue<>(1024 * 1024);
    }

    private void initialiseIndexes() {

        String archiveName = currentArchiveFile.getName()
                .split(".zip")[0];

        File typeIndexFile = new File(outputDir + archiveName + "-" + INDEX_TYPE_DAT_FILE);
        File stringIndexFile = new File(outputDir + archiveName + "-" + INDEX_STRING_DAT_FILE);
        File objectIndexFile = new File(outputDir + archiveName + "-" + INDEX_OBJECT_DAT_FILE);

        if (typeIndexFile.exists()) {
            typeIndexFile.delete();
        }
        if (stringIndexFile.exists()) {
            stringIndexFile.delete();
        }
        if (objectIndexFile.exists()) {
            objectIndexFile.delete();
        }

        typeInfoDocumentStringDiskPersistence
                = DiskPersistence.onPrimaryKeyInFile(TypeInfoDocument.TYPE_ID, typeIndexFile);
        stringInfoDocumentStringDiskPersistence
                = DiskPersistence.onPrimaryKeyInFile(StringInfoDocument.STRING_ID, stringIndexFile);
        objectInfoDocumentIntegerDiskPersistence
                = DiskPersistence.onPrimaryKeyInFile(ObjectInfoDocument.OBJECT_ID, objectIndexFile);

        typeInfoIndex = new ConcurrentIndexedCollection<>(typeInfoDocumentStringDiskPersistence);
        stringInfoIndex = new ConcurrentIndexedCollection<>(stringInfoDocumentStringDiskPersistence);
        objectInfoIndex = new ConcurrentIndexedCollection<>(objectInfoDocumentIntegerDiskPersistence);

        typeInfoIndex.addIndex(HashIndex.onAttribute(TypeInfoDocument.TYPE_NAME));
        stringInfoIndex.addIndex(InvertedRadixTreeIndex.onAttribute(StringInfoDocument.STRING_VALUE));
        objectInfoIndex.addIndex(HashIndex.onAttribute(ObjectInfoDocument.OBJECT_TYPE_ID));
    }

    public void drainQueueToIndex(
            List<ObjectInfoDocument> objectsToIndex,
            List<TypeInfoDocument> typesToIndex,
            List<StringInfoDocument> stringsToIndex
    ) {
        long start = System.currentTimeMillis();
        int itemCount = 0;

        itemCount += objectsToIndex.size();
        itemCount += typesToIndex.size();
        itemCount += stringsToIndex.size();
        if (itemCount == 0) {
            return;
        }

        objectInfoIndex.addAll(objectsToIndex);
//        System.err.println("Wrote " + objectsToIndex.size() + " object information to " +
//                objectInfoDocumentIntegerDiskPersistence.getFile().getName());
        typeInfoIndex.addAll(typesToIndex);
        stringInfoIndex.addAll(stringsToIndex);

        long end = System.currentTimeMillis();

        errorLogger.log("Took [" + (end - start) / 1000 + "] seconds to index [" + itemCount + "] items");
    }

    @Override
    public int fileCount() {
        return fileListToUpload.size();
    }

    public void indexObjectTypeEntry(long objectId, int typeId) {
        objectsToIndex.offer(new ObjectInfoDocument(objectId, typeId));
    }

    public void indexTypeEntry(int id, String typeName, byte[] typeInfoBytes) {
        typesToIndex.offer(new TypeInfoDocument(id, typeName, typeInfoBytes));
    }

    public void indexStringEntry(long id, String string) {
        stringsToIndex.offer(new StringInfoDocument(id, string));
    }

    public void completeArchive(
            BlockingQueue<StringInfoDocument> stringsToIndexTemp,
            BlockingQueue<ObjectInfoDocument> objectsToIndexTemp,
            BlockingQueue<TypeInfoDocument> typesToIndexTemp
    ) {

        indexWriterLock.lock();

        long start = System.currentTimeMillis();
        errorLogger.log("lock acquired to finish archive: " + currentArchiveFile.getName());

        try {


            long endTime = new Date().getTime();


            try {


                ZipEntry classWeaveEntry = new ZipEntry(WEAVE_DAT_FILE);
                archivedIndexOutputStream.putNextEntry(classWeaveEntry);
                FileOutputStream classWeaveFileRaw = new FileOutputStream(new File(outputDir + "class.weave.dat"));
                DataOutputStream weaveOutputStream = new DataOutputStream(archivedIndexOutputStream);

                List<byte[]> classesInfo = new LinkedList<>(this.classWeaves.subList(0, this.classWeaves.size()));
                weaveOutputStream.writeInt(classesInfo.size());
                for (byte[] classWeave : classesInfo) {
                    weaveOutputStream.write(classWeave);
                    classWeaveFileRaw.write(classWeave);
                }
                archivedIndexOutputStream.closeEntry();
                classWeaveFileRaw.close();


                ZipEntry indexEntry = new ZipEntry(INDEX_EVENTS_DAT_FILE);

                archivedIndexOutputStream.putNextEntry(indexEntry);
                DataOutputStream outputStream = new DataOutputStream(archivedIndexOutputStream);

                List<UploadFile> fileIndexBytesCopy = fileListToUpload;
                fileListToUpload = new LinkedList<>();

                outputStream.writeInt(fileIndexBytesCopy.size());
                for (UploadFile fileToUpload : fileIndexBytesCopy) {
                    outputStream.writeInt(fileToUpload.path.length());
                    outputStream.writeBytes(fileToUpload.path);
                    outputStream.writeLong(fileToUpload.threadId);


                    byte[] valueByteArray = BloomFilterConverter.toJson(fileToUpload.valueIdBloomFilter)
                            .toString()
                            .getBytes();
                    byte[] probeByteArray = BloomFilterConverter.toJson(fileToUpload.probeIdBloomFilter)
                            .toString()
                            .getBytes();


                    outputStream.writeInt(valueByteArray.length);
                    outputStream.write(valueByteArray);

                    outputStream.writeInt(probeByteArray.length);
                    outputStream.write(probeByteArray);
                }

                byte[] aggregatedValueFilterSerialized = BloomFilterConverter.toJson(aggregatedValueSet)
                        .toString()
                        .getBytes();
                byte[] aggregatedProbeFilterSerialized = BloomFilterConverter.toJson(aggregatedProbeIdSet)
                        .toString()
                        .getBytes();
//                System.err.println("Aggregated value filter for [" + currentArchiveFile.getName() + "] -> " + aggregatedValueFilterSerialized.length);
//                System.err.println("Aggregated probe filter for [" + currentArchiveFile.getName() +
//                        "] -> " + aggregatedProbeFilterSerialized.length);


                outputStream.writeInt(aggregatedValueFilterSerialized.length);
                outputStream.write(aggregatedValueFilterSerialized);

                outputStream.writeInt(aggregatedProbeFilterSerialized.length);
                outputStream.write(aggregatedProbeFilterSerialized);

                outputStream.writeLong(endTime);
                outputStream.flush();
                archivedIndexOutputStream.closeEntry();

                List<ObjectInfoDocument> pendingObjects = new LinkedList<>();
                List<TypeInfoDocument> pendingTypes = new LinkedList<>();
                List<StringInfoDocument> pendingStrings = new LinkedList<>();
                stringsToIndexTemp.drainTo(pendingStrings);
                objectsToIndexTemp.drainTo(pendingObjects);
                typesToIndexTemp.drainTo(pendingTypes);

                drainQueueToIndex(pendingObjects, pendingTypes, pendingStrings);


                String currentArchiveName = currentArchiveFile.getName()
                        .split(".zip")[0];


                ZipEntry stringIndexEntry = new ZipEntry(INDEX_STRING_DAT_FILE);
                archivedIndexOutputStream.putNextEntry(stringIndexEntry);
                Path stringIndexFilePath = FileSystems.getDefault()
                        .getPath(outputDir + currentArchiveName + "-" + INDEX_STRING_DAT_FILE);
                Files.copy(stringIndexFilePath, archivedIndexOutputStream);
                stringIndexFilePath.toFile()
                        .delete();
                archivedIndexOutputStream.closeEntry();

                ZipEntry typeIndexEntry = new ZipEntry(INDEX_TYPE_DAT_FILE);
                archivedIndexOutputStream.putNextEntry(typeIndexEntry);
                Path typeIndexFilePath = FileSystems.getDefault()
                        .getPath(outputDir + currentArchiveName + "-" + INDEX_TYPE_DAT_FILE);
                Files.copy(typeIndexFilePath, archivedIndexOutputStream);
                typeIndexFilePath.toFile()
                        .delete();
                archivedIndexOutputStream.closeEntry();

                ZipEntry objectIndexEntry = new ZipEntry(INDEX_OBJECT_DAT_FILE);
                archivedIndexOutputStream.putNextEntry(objectIndexEntry);
                Path objectIndexFilePath = FileSystems.getDefault()
                        .getPath(outputDir + currentArchiveName + "-" + INDEX_OBJECT_DAT_FILE);
                Files.copy(objectIndexFilePath, archivedIndexOutputStream);
                objectIndexFilePath.toFile()
                        .delete();
                archivedIndexOutputStream.closeEntry();

            } catch (IOException e) {
                errorLogger.log(e);
            } finally {
                archivedIndexOutputStream.close();
            }
        } catch (Exception e) {
            errorLogger.log(e);
        } finally {
            long end = System.currentTimeMillis();
            errorLogger.log(
                    "Took [" + ((end - start) / 1000) + "] seconds to complete archive: " + currentArchiveFile.getName());
            try {
                indexWriterLock.unlock();
            } catch (Exception e) {
                e.printStackTrace();
                // whut
            }
        }
    }

    public void close() {
//        shutdown = true;
        completeArchive(stringsToIndex, objectsToIndex, typesToIndex);
    }


    @Override
    public void writeFileEntry(UploadFile logFile) throws IOException {

        long currentTimestamp = System.currentTimeMillis();
        File fileToUpload = new File(logFile.path);
        fileListToUpload.add(logFile);

        String fileName = currentTimestamp + "@" + fileToUpload.getName();

        ZipEntry eventsFileZipEntry = new ZipEntry(fileName);
        archivedIndexOutputStream.putNextEntry(eventsFileZipEntry);
        FileInputStream fis = new FileInputStream(fileToUpload);
        copy(fis, archivedIndexOutputStream);
        fis.close();
        archivedIndexOutputStream.flush();
        archivedIndexOutputStream.closeEntry();
        long end = System.currentTimeMillis();

        errorLogger.log("[" + currentArchiveFile.getName() + "] Add files to archive: " + logFile.path + " " +
                "took - " + (end - currentTimestamp) / 1000 + " ms");
    }

    void copy(InputStream source, OutputStream target) throws IOException {
        byte[] buf = new byte[8192];
        int length;
        while ((length = source.read(buf)) > 0) {
            target.write(buf, 0, length);
        }
    }

    public void addValueId(long value) {
//        System.err.println("Add value to aggregated value filter: " + value);
        aggregatedValueSet.add(value);
    }

    public void addProbeId(int value) {
        aggregatedProbeIdSet.add(value);
    }
}