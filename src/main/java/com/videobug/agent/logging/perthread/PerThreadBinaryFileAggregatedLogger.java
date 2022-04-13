package com.videobug.agent.logging.perthread;

import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.util.AggregatedFileLogger;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.logging.util.NetworkClient;
import orestes.bloomfilter.BloomFilter;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class PerThreadBinaryFileAggregatedLogger implements
        AggregatedFileLogger,
        ThreadEventCountProvider {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 10000 * 3;
    public static final int WRITE_BYTE_BUFFER_SIZE = 1024 * 1024;
    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);
//    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    /**
     * Assign an integer to this thread.
     */
    private final ThreadLocal<Integer> threadId = ThreadLocal.withInitial(nextThreadId::getAndIncrement);

    private final BlockingQueue<UploadFile> fileList = new ArrayBlockingQueue<UploadFile>(1024);

    private final Map<Integer, OutputStream> threadFileMap = new HashMap<>();

    private final Map<Integer, String> currentFileMap = new HashMap<>();
    private final Map<Integer, AtomicInteger> count = new HashMap<>();
    private final NetworkClient networkClient;
    private final String hostname;
    private final FileNameGenerator fileNameGenerator;
    private final IErrorLogger errorLogger;
    private final ThreadLocal<byte[]> threadLocalByteBuffer = ThreadLocal.withInitial(() -> new byte[29]);
    private final Map<Integer, BloomFilter<Long>> valueIdFilterSet = new HashMap<>();
    private final Map<Integer, BloomFilter<Integer>> probeIdFilterSet = new HashMap<>();

    private final long currentTimestamp = System.currentTimeMillis();

    ScheduledExecutorService threadPoolExecutor5Seconds = Executors.newScheduledThreadPool(1);
    ExecutorService threadPoolExecutor = Executors.newFixedThreadPool(4);
    private RawFileCollector fileCollector = null;
    private FileEventCountThresholdChecker logFileTimeAgeChecker = null;
    private long eventId = 0;
    // set to true when we are unable to upload files to the server
    // this is reset every 10 mins to check if server is online again
    // files are deleted from the disk while this is true
    // no data events are recorded while this is true
    private boolean skipUploads = false;
    // when skipUploads is set to true due to 10 consecutive upload failures
    // a future is set reset skipUploads to false after 10 mins gap to check if server is back again
    private ScheduledFuture<?> skipResetFuture;
    private boolean shutdown;
    private DataOutputStream fileIndex;

    /**
     * Create an instance of stream.
     *
     * @param outputDirName location for generated files
     * @param logger        is to report errors that occur in this class.
     * @param token         to used to authentication for uploading logs
     * @param serverAddress endpoint for uploading logs
     * @param filesPerIndex number of files to store in one archive
     */
    public PerThreadBinaryFileAggregatedLogger(
            String outputDirName, IErrorLogger logger,
            String token, String sessionId, String serverAddress, int filesPerIndex) throws IOException {
//        this.sessionId = sessionId;
        this.hostname = NetworkClient.getHostname();
        this.networkClient = new NetworkClient(serverAddress, sessionId, token, logger);
        System.err.println("Session Id: [" + sessionId + "] on hostname [" + hostname + "]");
        this.errorLogger = logger;

        File outputDir = new File(outputDirName);
        outputDir.mkdirs();
        this.fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");


        fileCollector = new RawFileCollector(100,
                new FileNameGenerator(outputDir, "index-", ".zip"), fileList, errorLogger);

        writeHostname();
        writeTimestamp();


        System.out.printf("Create aggregated logger -> %s\n", currentFileMap.get(-1));

        threadPoolExecutor.submit(fileCollector);

        if (serverAddress != null && serverAddress.length() > 5) {
            logFileTimeAgeChecker = new FileEventCountThresholdChecker(
                    threadFileMap, this,
                    (theThreadId) -> {
                        try {
                            prepareNextFile(theThreadId);
                        } catch (IOException e) {
                            errorLogger.log(e);
                        }
                        return null;
                    });
            threadPoolExecutor5Seconds.
                    scheduleAtFixedRate(logFileTimeAgeChecker, 0, 200, TimeUnit.MILLISECONDS);
        }
    }


    private OutputStream getStreamForThread(int threadId) {
        if (threadFileMap.containsKey(threadId)) {
            return threadFileMap.get(threadId);
        }
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

        OutputStream out = threadFileMap.get(currentThreadId);
        if (out != null) {
            String currentFile = currentFileMap.get(currentThreadId);
//            errorLogger.log("flush existing file for thread [" + currentThreadId + "] -> " + currentFile);
            out.flush();
            out.close();

            BloomFilter<Long> valueIdBloomFilter = valueIdFilterSet.get(currentThreadId);
            BloomFilter<Integer> probeIdBloomFilter = probeIdFilterSet.get(currentThreadId);

            count.put(currentThreadId, new AtomicInteger(0));
            valueIdFilterSet.put(currentThreadId,
                    BloomFilterUtil.newBloomFilterForValues(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));
            probeIdFilterSet.put(currentThreadId,
                    BloomFilterUtil.newBloomFilterForProbes(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));

            UploadFile newLogFile = new UploadFile(currentFile, currentThreadId, valueIdBloomFilter, probeIdBloomFilter);
            fileList.offer(newLogFile);

//            fileQueueAppender.writeBytes(e -> {
//                e.write(newLogFile.toBytes());
//            });


        }

        if (shutdown) {
            return;
        }
        File nextFile = fileNameGenerator.getNextFile(String.valueOf(currentThreadId));
        currentFileMap.put(currentThreadId, nextFile.getPath());
        out = new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE);
        threadFileMap.put(currentThreadId, out);

        count.put(currentThreadId, new AtomicInteger(0));
        valueIdFilterSet.put(currentThreadId,
                BloomFilterUtil.newBloomFilterForValues(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));
        probeIdFilterSet.put(currentThreadId,
                BloomFilterUtil.newBloomFilterForProbes(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));

        writeTimestamp();
    }

    /**
     * Close the stream.
     */
    public void close() {
        for (Map.Entry<Integer, OutputStream> threadStreamEntrySet : threadFileMap.entrySet()) {
            OutputStream out = threadStreamEntrySet.getValue();
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

        OutputStream out = getStreamForThread(currentThreadId);
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
            fileCollector.indexObjectTypeEntry(id, (int) typeId);

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
            fileCollector.indexStringEntry(id, stringObject);
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

    public void writeEvent(int probeId, long valueId) {
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


            buffer[17] = (byte) (probeId >>> 24);
            buffer[18] = (byte) (probeId >>> 16);
            buffer[19] = (byte) (probeId >>> 8);
            buffer[20] = (byte) (probeId >>> 0);


            buffer[21] = (byte) (valueId >>> 56);
            buffer[22] = (byte) (valueId >>> 48);
            buffer[23] = (byte) (valueId >>> 40);
            buffer[24] = (byte) (valueId >>> 32);
            buffer[25] = (byte) (valueId >>> 24);
            buffer[26] = (byte) (valueId >>> 16);
            buffer[27] = (byte) (valueId >>> 8);
            buffer[28] = (byte) (valueId >>> 0);


            getStreamForThread(currentThreadId).write(buffer);
            int threadEventCount = getThreadEventCount(currentThreadId).addAndGet(1);
//            if (threadEventCount > MAX_EVENTS_PER_FILE) {
//                prepareNextFile(currentThreadId);
//            }

            valueIdFilterSet.get(currentThreadId).add(valueId);
            fileCollector.addValueId(valueId);
            probeIdFilterSet.get(currentThreadId).add(probeId);
            fileCollector.addProbeId(probeId);

            eventId++;
        } catch (IOException e) {
            errorLogger.log(e);
        }
//            System.err.println("Write new event - 4," + id + "," + value + " = " + this.bytesWritten);

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

    public void writeNewTypeRecord(int typeId, String typeName, String toString) {

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
            fileCollector.indexTypeEntry(typeId, typeName);

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

    public void shutdown() throws IOException {
        skipUploads = true;
        shutdown = true;

        fileCollector.shutdown();
        threadPoolExecutor5Seconds.shutdown();
        threadPoolExecutor.shutdown();


        if (logFileTimeAgeChecker != null) {
            logFileTimeAgeChecker.run();
        }

    }

    @Override
    public AtomicInteger getThreadEventCount(int currentThreadId) {
        if (!count.containsKey(currentThreadId)) {
            count.put(currentThreadId, new AtomicInteger(0));
        }
        return count.get(currentThreadId);
    }
}
