package com.videobug.agent.logging.util;

import com.videobug.agent.logging.IErrorLogger;
import orestes.bloomfilter.BloomFilter;
import orestes.bloomfilter.FilterBuilder;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class PerThreadBinaryFileAggregatedLogger implements AggregatedFileLogger {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 10000 * 3;
    public static final int WRITE_BYTE_BUFFER_SIZE = 1024 * 1024;
    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);
    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    private final Lock indexWriterLock = new ReentrantLock();
    /**
     * Assign an integer to this thread.
     */
    private final ThreadLocal<Integer> threadId = ThreadLocal.withInitial(nextThreadId::getAndIncrement);

    private final BlockingQueue<UploadFile> fileList = new ArrayBlockingQueue<UploadFile>(1024);

    private final Map<Integer, BufferedOutputStream> threadFileMap = new HashMap<>();
    private final Map<Integer, String> currentFileMap = new HashMap<>();
    private final Map<Integer, AtomicInteger> count = new HashMap<>();
    private final NetworkClient networkClient;
    private final String hostname;
    private final FileNameGenerator fileNameGenerator;
    private final FileNameGenerator indexFileNameGenerator;
    private final IErrorLogger errorLogger;
    private final ThreadLocal<byte[]> threadLocalByteBuffer = ThreadLocal.withInitial(() -> new byte[29]);

    private final Map<Integer, BloomFilter<Long>> valueIdFilterSet = new HashMap<>();
    private final Map<Integer, BloomFilter<Integer>> probeIdFilterSet = new HashMap<>();
    private final ArchivedIndexWriter archivedIndexWriter;
    private final String sessionId;
    private UploaderCron uploadCron = null;
    ScheduledExecutorService threadPoolExecutor5Seconds = Executors.newScheduledThreadPool(1);
    ExecutorService threadPoolExecutor = Executors.newFixedThreadPool(1);
    private LogFileTimeExpiry logFileTimeAgeChecker = null;
    private int indexFileCount = 0;
    private BloomFilter<Long> aggregatedValueSet = newBloomFilterForValues();
    private BloomFilter<Integer> aggregatedProbeIdSet = newBloomFilterForProbes();
    private long eventId = 0;
    private long currentTimestamp = System.currentTimeMillis();
    // set to true when we are unable to upload files to the server
    // this is reset every 10 mins to check if server is online again
    // files are deleted from the disk while this is true
    // no data events are recorded while this is true
    private boolean skipUploads = false;
    // when skipUploads is set to true due to 10 consecutive upload failures
    // a future is set reset skipUploads to false after 10 mins gap to check if server is back again
    private ScheduledFuture<?> skipResetFuture;
    private ZipOutputStream archivedIndexOutputStream = null;
    private boolean shudown;

    /**
     * Create an instance of stream.
     *
     * @param outputDirName location for generated files
     * @param logger        is to report errors that occur in this class.
     * @param token         to used to authentication for uploading logs
     * @param serverAddress endpoint for uploading logs
     * @param filesPerIndex
     */
    public PerThreadBinaryFileAggregatedLogger(
            String outputDirName, IErrorLogger logger,
            String token, String sessionId, String serverAddress, int filesPerIndex) throws IOException {
        this.sessionId = sessionId;
        this.hostname = NetworkClient.getHostname();
        this.networkClient = new NetworkClient(serverAddress, sessionId, token, logger);
        System.err.println("Session Id: [" + sessionId + "] on hostname [" + hostname + "]");
        this.errorLogger = logger;

        File outputDir = new File(outputDirName);
        outputDir.mkdirs();
        this.fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
        this.indexFileNameGenerator = new FileNameGenerator(outputDir, "index-", ".zip");


        writeHostname();
        writeTimestamp();
        archivedIndexWriter = new ArchivedIndexWriter();

        System.out.printf("Create aggregated logger -> %s\n", currentFileMap.get(-1));
        if (serverAddress != null && serverAddress.length() > 5) {
            uploadCron = new UploaderCron(10, filesPerIndex);
            threadPoolExecutor.submit(uploadCron);
            logFileTimeAgeChecker = new LogFileTimeExpiry();
            threadPoolExecutor5Seconds.scheduleAtFixedRate(logFileTimeAgeChecker, 0, 200, TimeUnit.MILLISECONDS);
        }
    }

    private BufferedOutputStream getStreamForThread(int threadId) {
        if (threadFileMap.containsKey(threadId)) {
            return threadFileMap.get(threadId);
        }
        BufferedOutputStream threadStream = null;
        try {
            prepareNextFile(threadId);
        } catch (IOException e) {
            errorLogger.log(e);
        }
        return threadFileMap.get(threadId);
    }

    private synchronized void prepareNextFile(int currentThreadId) throws IOException {


        if (count.containsKey(currentThreadId) && threadFileMap.get(currentThreadId) != null) {
            int eventCount = count.get(currentThreadId).get();
            if (eventCount < 1) {
                return;
            }
        }

        BufferedOutputStream out = threadFileMap.get(currentThreadId);
        if (out != null) {
            String currentFile = currentFileMap.get(currentThreadId);
            errorLogger.log("flush existing file for thread [" + currentThreadId + "] -> " + currentFile);
            out.flush();
            out.close();

            BloomFilter<Long> valueIdBloomFilter = valueIdFilterSet.get(currentThreadId);
            BloomFilter<Integer> probeIdBloomFilter = probeIdFilterSet.get(currentThreadId);

            count.put(currentThreadId, new AtomicInteger(0));
            valueIdFilterSet.put(currentThreadId, newBloomFilterForValues());
            probeIdFilterSet.put(currentThreadId, newBloomFilterForProbes());

            UploadFile newLogFile = new UploadFile(currentFile, currentThreadId, valueIdBloomFilter, probeIdBloomFilter);
            boolean offerTaken = fileList.offer(newLogFile);


            if (!offerTaken) {
                errorLogger.log(String.format(
                        "warning, file upload queue is full, deleting and skipping file [%s]", currentFile
                ));
                new File(currentFile).delete();
            }

        }

        if (shudown) {
            return;
        }
        File nextFile = fileNameGenerator.getNextFile(String.valueOf(currentThreadId));
        currentFileMap.put(currentThreadId, nextFile.getAbsolutePath());
        out = new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE);
        threadFileMap.put(currentThreadId, out);

        count.put(currentThreadId, new AtomicInteger(0));
        valueIdFilterSet.put(currentThreadId, newBloomFilterForValues());
        probeIdFilterSet.put(currentThreadId, newBloomFilterForProbes());

        writeTimestamp();
    }

    /**
     * Close the stream.
     */
    public void close() {
        for (Map.Entry<Integer, BufferedOutputStream> threadStreamEntrySet : threadFileMap.entrySet()) {
            BufferedOutputStream out = threadStreamEntrySet.getValue();
            int streamTheadId = threadStreamEntrySet.getKey();
            System.out.print("Close file for thread [" + streamTheadId + "]\n");
            try {
                out.close();
            } catch (IOException e) {
                errorLogger.log(e);
            }
        }


    }

    public void writeNewObjectType(long id, long typeId) {
//        err.log("new object[" + id + "] type [" + typeId + "] record");
        if (skipUploads) {
            return;
        }

        int currentThreadId = threadId.get();

        BufferedOutputStream out = getStreamForThread(currentThreadId);
        int bytesToWrite = 1 + 8 + 8;

        try {

//            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
//            DataOutputStream tempOut = new DataOutputStream(baos);
//            tempOut.writeByte(1);
//            tempOut.writeLong(id);
//            tempOut.writeLong(typeId);

            byte[] buffer = threadLocalByteBuffer.get();
            buffer[0] = 1;


            buffer[1] = (byte) (id >>> 56);
            buffer[2] = (byte) (id >>> 48);
            buffer[3] = (byte) (id >>> 40);
            buffer[4] = (byte) (id >>> 32);
            buffer[5] = (byte) (id >>> 24);
            buffer[6] = (byte) (id >>> 16);
            buffer[7] = (byte) (id >>> 8);
            buffer[8] = (byte) (id >>> 0);


            buffer[9] = (byte) (typeId >>> 56);
            buffer[10] = (byte) (typeId >>> 48);
            buffer[11] = (byte) (typeId >>> 40);
            buffer[12] = (byte) (typeId >>> 32);
            buffer[13] = (byte) (typeId >>> 24);
            buffer[14] = (byte) (typeId >>> 16);
            buffer[15] = (byte) (typeId >>> 8);
            buffer[16] = (byte) (typeId >>> 0);


            out.write(buffer, 0, 17);
            getThreadEventCount(currentThreadId).addAndGet(1);
        } catch (IOException e) {
            errorLogger.log(e);
        }
        // System.err.println("Write new object - 1," + id + "," + typeId.length() + " - " + typeId + " = " + this.bytesWritten);

    }

    public void writeNewString(long id, String stringObject) {
        int bytesToWrite = 1 + 8 + 4 + stringObject.length();

        int currentThreadId = threadId.get();

        try {
            if (getThreadEventCount(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            errorLogger.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
//            err.log("Write string [" + id + "] -> [" + stringObject.length() + "] -> [" + stringObject + "]");
            tempOut.writeByte(2);
            tempOut.writeLong(id);
            byte[] bytes = stringObject.getBytes();
            tempOut.writeInt(bytes.length);
            tempOut.write(bytes);
            getStreamForThread(threadId.get()).write(baos.toByteArray());
            getThreadEventCount(currentThreadId).addAndGet(1);
        } catch (IOException e) {
            errorLogger.log(e);
        }
//        writeString(stringObject);

        // System.err.println("Write new string - 2," + id + "," + stringObject.length() + " - " + stringObject + " = " + this.bytesWritten);


    }

    public void writeNewException(byte[] exceptionBytes) {
        int bytesToWrite = 1 + 4 + exceptionBytes.length;

        int currentThreadId = threadId.get();

        try {
            if (getThreadEventCount(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            errorLogger.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(3);
            tempOut.writeInt(exceptionBytes.length);
            tempOut.write(exceptionBytes);
            getStreamForThread(threadId.get()).write(baos.toByteArray());
            getThreadEventCount(currentThreadId).addAndGet(1);
        } catch (IOException e) {
            e.printStackTrace();
        }
//        writeString(toString);
        // System.err.println("Write new exception - 3," + toString.length() + " - " + toString + " = " + this.bytesWritten);
    }

    public void writeEvent(int id, long value) {
        if (skipUploads) {
            return;
        }

        long timestamp = currentTimestamp;


        int currentThreadId = threadId.get();

        try {

            byte[] buffer = threadLocalByteBuffer.get();
            buffer[0] = 4;


            buffer[1] = (byte) (eventId >>> 56);
            buffer[2] = (byte) (eventId >>> 48);
            buffer[3] = (byte) (eventId >>> 40);
            buffer[4] = (byte) (eventId >>> 32);
            buffer[5] = (byte) (eventId >>> 24);
            buffer[6] = (byte) (eventId >>> 16);
            buffer[7] = (byte) (eventId >>> 8);
            buffer[8] = (byte) (eventId >>> 0);


            buffer[9] = (byte) (timestamp >>> 56);
            buffer[10] = (byte) (timestamp >>> 48);
            buffer[11] = (byte) (timestamp >>> 40);
            buffer[12] = (byte) (timestamp >>> 32);
            buffer[13] = (byte) (timestamp >>> 24);
            buffer[14] = (byte) (timestamp >>> 16);
            buffer[15] = (byte) (timestamp >>> 8);
            buffer[16] = (byte) (timestamp >>> 0);


            buffer[17] = (byte) (id >>> 24);
            buffer[18] = (byte) (id >>> 16);
            buffer[19] = (byte) (id >>> 8);
            buffer[20] = (byte) (id >>> 0);


            buffer[21] = (byte) (value >>> 56);
            buffer[22] = (byte) (value >>> 48);
            buffer[23] = (byte) (value >>> 40);
            buffer[24] = (byte) (value >>> 32);
            buffer[25] = (byte) (value >>> 24);
            buffer[26] = (byte) (value >>> 16);
            buffer[27] = (byte) (value >>> 8);
            buffer[28] = (byte) (value >>> 0);


            getStreamForThread(currentThreadId).write(buffer);
            int threadEventCount = getThreadEventCount(currentThreadId).addAndGet(1);
            if (threadEventCount > MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }

            valueIdFilterSet.get(currentThreadId).add(value);
            probeIdFilterSet.get(currentThreadId).add(id);

            eventId++;
        } catch (IOException e) {
            errorLogger.log(e);
        }
//            System.err.println("Write new event - 4," + id + "," + value + " = " + this.bytesWritten);

    }

    private AtomicInteger getThreadEventCount(int currentThreadId) {
        if (!count.containsKey(currentThreadId)) {
            count.put(currentThreadId, new AtomicInteger(0));
        }
        return count.get(currentThreadId);
    }

    public void writeHostname() {

        try {
            int bytesToWrite = 1 + 4 + hostname.length();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);


            tempOut.writeByte(8);
            tempOut.writeInt(hostname.length());
            tempOut.writeBytes(hostname);
            getStreamForThread(threadId.get()).write(baos.toByteArray());

        } catch (IOException e) {
            errorLogger.log(e);
        }
    }

    public void writeTimestamp() {
        int bytesToWrite = 1 + 8;
        long timeStamp = currentTimestamp;


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(7);      // 1
            tempOut.writeLong(timeStamp); // 8
            getStreamForThread(threadId.get()).write(baos.toByteArray());
        } catch (IOException e) {
            errorLogger.log(e);
        }

    }

    public void writeNewTypeRecord(String toString) {

        int bytesToWrite = 1 + 4 + toString.length();
        int currentThreadId = threadId.get();

        try {

            if (getThreadEventCount(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            errorLogger.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(5);              // 1
            tempOut.writeInt(toString.length());  // 4
            tempOut.write(toString.getBytes());   // length
            getStreamForThread(currentThreadId).write(baos.toByteArray());
            getThreadEventCount(currentThreadId).addAndGet(1);
        } catch (IOException e) {
            errorLogger.log(e);
            e.printStackTrace();
        }
//        writeString(toString);
        // System.err.println("Write type record - 5," + toString.length() + " - " + toString + " = " + this.bytesWritten);
    }

    public void writeWeaveInfo(byte[] byteArray) {
        int currentThreadId = threadId.get();
        try {

            if (getThreadEventCount(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
            int bytesToWrite = 1 + 4 + byteArray.length;


            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);


            tempOut.writeByte(6);
            tempOut.writeInt(byteArray.length);
            tempOut.write(byteArray);
            getStreamForThread(currentThreadId).write(baos.toByteArray());
            getThreadEventCount(currentThreadId).addAndGet(1);
            // System.err.println("Write weave 6," + byteArray.length + " - " + new String(byteArray) + " = " + this.bytesWritten);
        } catch (IOException e) {
            errorLogger.log(e);
        }

    }

    private BloomFilter<Long> newBloomFilterForValues() {
        return new FilterBuilder(1024 * 16, 0.01).buildBloomFilter();
    }

    private BloomFilter<Integer> newBloomFilterForProbes() {
        return new FilterBuilder(1024 * 16, 0.01).buildBloomFilter();
    }

    public void shutdown() {
        skipUploads = true;
        shudown = true;

        archivedIndexWriter.shutdown();
        uploadCron.shutdown();
        threadPoolExecutor5Seconds.shutdown();
        threadPoolExecutor.shutdown();


        if (logFileTimeAgeChecker != null) {
            logFileTimeAgeChecker.run();
        }

        uploadCron.upload();
        archivedIndexWriter.run();

    }

    private static class UploadFile {
        final public String path;
        final public long threadId;
        final public BloomFilter<Long> valueIdBloomFilter;
        final public BloomFilter<Integer> probeIdBloomFilter;

        public UploadFile(String s, long currentThreadId,
                          BloomFilter<Long> valueIdBloomFilter,
                          BloomFilter<Integer> probeIdBloomFilter) {
            this.path = s;
            this.threadId = currentThreadId;
            this.valueIdBloomFilter = valueIdBloomFilter;
            this.probeIdBloomFilter = probeIdBloomFilter;
        }
    }

    public class UploaderCron implements Runnable {
        public static final int MAX_CONSECUTIVE_FAILURE_COUNT = 10;
        public static final int FAILURE_SLEEP_DELAY = 10;
        public final int FILES_IN_ONE_INDEX;
        private boolean shutdown = false;

        public int continuousUploadFailureCount = 0;

        public UploaderCron(int continuousUploadFailureCount, int filesInOneIndex) {
            FILES_IN_ONE_INDEX = filesInOneIndex;
            this.continuousUploadFailureCount = continuousUploadFailureCount;
        }

        public void shutdown() {
            shutdown = true;
        }

        public void upload() {
            try {

                UploadFile logFile = fileList.take();
                File fileToUpload = new File(logFile.path);
                errorLogger.log("Take file [" + logFile.path + "]");


                String fileName = currentTimestamp + "@" + fileToUpload.getName();
                indexWriterLock.lock();

                ByteArrayOutputStream fileBaos = new ByteArrayOutputStream();
                DataOutputStream fileOut = new DataOutputStream(fileBaos);

                long start = System.currentTimeMillis();
                fileOut.write(logFile.valueIdBloomFilter.getBitSet().toByteArray());
                fileOut.write(logFile.probeIdBloomFilter.getBitSet().toByteArray());
                errorLogger.log("BF to byte[] took [" + (System.currentTimeMillis() - start) + "] ms");

                start = System.currentTimeMillis();
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

                continuousUploadFailureCount = 0;
                fileToUpload.delete();

            } catch (IOException e) {
                System.err.println("Failed to upload file: " + e.getMessage());
                errorLogger.log(e);
                continuousUploadFailureCount++;
                if (continuousUploadFailureCount > MAX_CONSECUTIVE_FAILURE_COUNT) {
                    errorLogger.log("continuous " + MAX_CONSECUTIVE_FAILURE_COUNT
                            + " file upload failure, skipping file uploads for 10 mins");
                    skipUploads = true;
                    skipResetFuture = threadPoolExecutor5Seconds.scheduleWithFixedDelay(() -> {
                        errorLogger.log("resetting skip uploads to false after 10 mins delay since last failure");
                        skipUploads = false;
                        skipResetFuture.cancel(false);
                    }, FAILURE_SLEEP_DELAY, 20, TimeUnit.MINUTES);
                }
            } catch (InterruptedException e) {
                errorLogger.log("file upload cron interrupted, shutting down");
            } finally {
                indexWriterLock.unlock();
                if (indexFileCount >= FILES_IN_ONE_INDEX) {
                    errorLogger.log("write index file");
                    archivedIndexWriter.run();
                }
            }
        }
        @Override
        public void run() {
            System.err.println("Sending dumps to: " + networkClient.getServerUrl());
            while (true) {
                if (shudown) {
                    return;
                }
                long start = System.currentTimeMillis();
                upload();
                long timeToProcessFile = System.currentTimeMillis() - start;
                errorLogger.log("adding file took [" + timeToProcessFile + "] ms");
            }
        }
    }

    class ArchivedIndexWriter {

        boolean shutdown;

        public ArchivedIndexWriter() throws IOException {
            File nextIndexFile = indexFileNameGenerator.getNextFile();
            errorLogger.log("prepare next index archive: " + nextIndexFile.getAbsolutePath());
            archivedIndexOutputStream = new ZipOutputStream(new FileOutputStream(nextIndexFile));

        }

        public void run() {
            try {

                if (!indexWriterLock.tryLock()) {
                    return;
                }

                BloomFilter<Long> valueSetBloomFilter = aggregatedValueSet;
                BloomFilter<Integer> probeSetBloomFilter = aggregatedProbeIdSet;

                aggregatedValueSet = newBloomFilterForValues();
                aggregatedProbeIdSet = newBloomFilterForProbes();


                byte[] valueIdsFilterArray = valueSetBloomFilter.getBitSet().toByteArray();
                byte[] probeIdsFilterArray = probeSetBloomFilter.getBitSet().toByteArray();
                try {

                    ZipEntry indexEntry = new ZipEntry("bug.video.index");
                    archivedIndexOutputStream.putNextEntry(indexEntry);
                    archivedIndexOutputStream.write(sessionId.getBytes());
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

            // need to store value/probe ids bitset per file in this index file

        }

        private void prepareIndexFile() throws IOException {
            if (archivedIndexOutputStream != null && indexFileCount > 0) {
                archivedIndexOutputStream.flush();
                archivedIndexOutputStream.close();
            }
            if (shutdown || indexFileCount == 0) {
                return;
            }
            File nextIndexFile = indexFileNameGenerator.getNextFile();
            errorLogger.log("prepare next index archive: " + nextIndexFile.getAbsolutePath());
            archivedIndexOutputStream = new ZipOutputStream(new FileOutputStream(nextIndexFile));
            indexFileCount = 0;
        }

        public void shutdown() {
            shutdown = true;
        }
    }

    class LogFileTimeExpiry implements Runnable {

        @Override
        public void run() {
            try {
                currentTimestamp = System.currentTimeMillis();
                Integer[] keySet = threadFileMap.keySet().toArray(new Integer[0]);
                for (Integer theThreadId : keySet) {

                    int eventCount = getThreadEventCount(theThreadId).get();

//                    errorLogger.log("200 ms log file checker: [ "
//                            + theThreadId + " / " + keySet.length
//                            + " ] threads open has [" + eventCount + "] events");
                    if (eventCount > 0) {
                        errorLogger.log("log file for thread [" + theThreadId + "] has : " + eventCount
                                + " events in file" +
                                " for thread [" + theThreadId + "] in file ["
                                + currentFileMap.get(theThreadId) + "]");
                        prepareNextFile(theThreadId);
                    }
                }
            } catch (IOException e) {
                errorLogger.log(e);
            }
        }
    }

}
