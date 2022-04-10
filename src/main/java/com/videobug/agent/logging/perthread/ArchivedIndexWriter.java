package com.videobug.agent.logging.perthread;

import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.util.FileNameGenerator;
import orestes.bloomfilter.BloomFilter;

import java.io.*;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class ArchivedIndexWriter implements IndexOutputStream {

    private final FileNameGenerator indexFileNameGenerator;
    private final IErrorLogger errorLogger;
    private final Lock indexWriterLock = new ReentrantLock();
    private final List<UploadFile> fileIndexBytes = new LinkedList<>();
    private final int filesPerIndex;
    private final BloomFilter<Long> aggregatedValueSet = BloomFilterUtil.newBloomFilterForValues();
    private final BloomFilter<Integer> aggregatedProbeIdSet = BloomFilterUtil.newBloomFilterForProbes();
    boolean shutdown;
    private ZipOutputStream archivedIndexOutputStream;
    private int indexFileCount = 0;
    private int continuousUploadFailureCount = 0;

    public ArchivedIndexWriter(
            FileNameGenerator indexFileNameGenerator,
            IErrorLogger errorLogger, int filesPerIndex) throws IOException {
        this.indexFileNameGenerator = indexFileNameGenerator;
        this.errorLogger = errorLogger;
        this.filesPerIndex = filesPerIndex;
        File nextIndexFile = this.indexFileNameGenerator.getNextFile();
        this.errorLogger.log("prepare next index archive: " + nextIndexFile.getAbsolutePath());
        archivedIndexOutputStream = new ZipOutputStream(new FileOutputStream(nextIndexFile));

    }

    public void run(BloomFilter<Long> valueSetBloomFilter, BloomFilter<Integer> probeSetBloomFilter) {
        try {

            if (!indexWriterLock.tryLock()) {
                return;
            }


            byte[] valueIdsFilterArray = valueSetBloomFilter.getBitSet().toByteArray();
            byte[] probeIdsFilterArray = probeSetBloomFilter.getBitSet().toByteArray();
            try {

                ZipEntry indexEntry = new ZipEntry("bug.video.index");
                archivedIndexOutputStream.putNextEntry(indexEntry);
                // todo: implement
                archivedIndexOutputStream.write(fileIndexBytes.toString().getBytes());
                archivedIndexOutputStream.write(valueIdsFilterArray);
                archivedIndexOutputStream.write(probeIdsFilterArray);
                archivedIndexOutputStream.closeEntry();

                prepareIndexFile();

            } catch (IOException e) {
                errorLogger.log(e);
            }
        } finally {
            indexWriterLock.unlock();
        }
    }

    private void prepareIndexFile() throws IOException {
        if (archivedIndexOutputStream != null) {
            archivedIndexOutputStream.flush();
            archivedIndexOutputStream.close();
        }
        if (shutdown && indexFileCount == 0) {
            return;
        }
        File nextIndexFile = indexFileNameGenerator.getNextFile();
        errorLogger.log("prepare next index archive: " + nextIndexFile.getAbsolutePath());
        archivedIndexOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(nextIndexFile)));
        indexFileCount = 0;
    }

    public void shutdown() {
        shutdown = true;
    }

    @Override
    public void writeFileEntry(UploadFile logFile) throws IOException {

        File fileToUpload = new File(logFile.path);

        long currentTimestamp = System.currentTimeMillis();
        String fileName = currentTimestamp + "@" + fileToUpload.getName();

        long start;
        start = System.currentTimeMillis();

        ByteArrayOutputStream fileBaos = new ByteArrayOutputStream();
        DataOutputStream fileOut = new DataOutputStream(fileBaos);

        start = System.currentTimeMillis();
        fileOut.write(logFile.valueIdBloomFilter.getBitSet().toByteArray());
        fileOut.write(logFile.probeIdBloomFilter.getBitSet().toByteArray());
        errorLogger.log("BF to byte[] took [" + (System.currentTimeMillis() - start) + "] ms");

        indexWriterLock.lock();
        try {


            ZipEntry fileZipEntry = new ZipEntry(fileName);
            archivedIndexOutputStream.putNextEntry(fileZipEntry);
            archivedIndexOutputStream.write(fileBaos.toByteArray());
            archivedIndexOutputStream.closeEntry();
            errorLogger.log("writing 1st entry took [" + (System.currentTimeMillis() - start) + "] ms");

            start = System.currentTimeMillis();
            ZipEntry eventsFileZipEntry = new ZipEntry(fileName + ".events");
            archivedIndexOutputStream.putNextEntry(eventsFileZipEntry);
            FileInputStream fis = new FileInputStream(fileToUpload);
            InputStream fileInputStream = new BufferedInputStream(fis);
            fileInputStream.transferTo(archivedIndexOutputStream);
            fis.close();
            archivedIndexOutputStream.closeEntry();

            errorLogger.log("writing 2nd entry took [" + (System.currentTimeMillis() - start) + "] ms");
            archivedIndexOutputStream.flush();

            start = System.currentTimeMillis();
            aggregatedValueSet.union(logFile.valueIdBloomFilter);
            aggregatedProbeIdSet.union(logFile.probeIdBloomFilter);
            long tts = System.currentTimeMillis() - start;
            errorLogger.log("union took [" + tts + "] ms");

            indexFileCount++;

            if (indexFileCount > filesPerIndex) {
                prepareIndexFile();
            }

            continuousUploadFailureCount = 0;
        } finally {
            indexWriterLock.unlock();
        }
    }
}