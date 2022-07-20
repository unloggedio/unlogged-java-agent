package com.videobug.agent.logging.perthread;

import com.insidious.common.BloomFilterUtil;
import com.insidious.common.UploadFile;
import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.util.AggregatedFileLogger;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.logging.util.NetworkClient;
import orestes.bloomfilter.BloomFilter;

import java.io.*;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
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
    public static final int MAX_EVENTS_PER_FILE = 10000 * 50;
    public static final int WRITE_BYTE_BUFFER_SIZE = 1024 * 1024 * 16;
    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);
    private static final int TASK_QUEUE_CAPACITY = 1024 * 1024 * 32;
//    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    /**
     * Assign an integer to this thread.
     */
    private final ThreadLocal<Integer> threadId = ThreadLocal.withInitial(nextThreadId::getAndIncrement);

    private final BlockingQueue<UploadFile> fileList;

    private final Map<Integer, OutputStream> threadFileMap = new HashMap<>();

    private final Map<Integer, String> currentFileMap = new HashMap<>();
    private final Map<Integer, AtomicInteger> count = new HashMap<>();
    private final String hostname;
    private final FileNameGenerator fileNameGenerator;
    private final IErrorLogger errorLogger;
    private final ThreadLocal<byte[]> threadLocalByteBuffer = ThreadLocal.withInitial(() -> {
        byte[] bytes = new byte[29];
        bytes[0] = 4;
        return bytes;
    });
    private final Map<Integer, BloomFilter<Long>> valueIdFilterSet = new HashMap<>();
    private final Map<Integer, BloomFilter<Integer>> probeIdFilterSet = new HashMap<>();
    ScheduledExecutorService threadPoolExecutor5Seconds = Executors.newScheduledThreadPool(1);
    ExecutorService threadPoolExecutor = Executors.newFixedThreadPool(4);
    private long currentTimestamp = System.currentTimeMillis();
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
    private final OffLoadTaskPayload[] TaskQueueArray = new OffLoadTaskPayload[TASK_QUEUE_CAPACITY];
    private int offloadTaskQueueReadIndex;

    /**
     * Create an instance of stream.
     *
     * @param fileNameGenerator file generator for output data
     * @param logger            is to report errors that occur in this class.
     * @param fileCollector     collects the dataEvent log files, creates indexes,
     */
    public PerThreadBinaryFileAggregatedLogger(
            FileNameGenerator fileNameGenerator, IErrorLogger logger,
            RawFileCollector fileCollector) {
//        this.sessionId = sessionId;
        this.hostname = NetworkClient.getHostname();
        this.errorLogger = logger;

        this.fileNameGenerator = fileNameGenerator;

        this.fileCollector = fileCollector;
        this.fileList = fileCollector.getFileQueue();

//        System.out.printf("[videobug] create aggregated logger -> %s\n", currentFileMap.get(-1));

        threadPoolExecutor.submit(fileCollector);
        threadPoolExecutor.submit(new Runnable() {
            @Override
            public void run() {

                while (true) {
                    try {
                        OffLoadTaskPayload task = TaskQueueArray[offloadTaskQueueReadIndex % TASK_QUEUE_CAPACITY];
                        if (task == null) {
                            Thread.sleep(10);
                            continue;
                        }
                        TaskQueueArray[offloadTaskQueueReadIndex] = null;

                        getThreadEventCount(task.threadId).addAndGet(1);


                        valueIdFilterSet.get(task.threadId).add(task.value);
                        fileCollector.addValueId(task.value);
                        probeIdFilterSet.get(task.threadId).add(task.probeId);
                        fileCollector.addProbeId(task.probeId);

                        offloadTaskQueueReadIndex += 1;

                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable throwable) {

                    }
                }

            }
        });

        logFileTimeAgeChecker = new FileEventCountThresholdChecker(
                threadFileMap, this,
                (theThreadId) -> {
                    try {
                        currentTimestamp = System.currentTimeMillis();
                        prepareNextFile(theThreadId);
                    } catch (IOException e) {
                        errorLogger.log(e);
                    }
                    return null;
                }, errorLogger);
        threadPoolExecutor5Seconds.
                scheduleAtFixedRate(logFileTimeAgeChecker, 0, 731, TimeUnit.MILLISECONDS);
        // 731 because it
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

//        errorLogger.log("prepare next file [" + currentThreadId + "] " + Arrays.toString(new Exception().getStackTrace()));

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
            try {
                out.close();
            } catch (ClosedChannelException cce) {
                            errorLogger.log("[videobug] channel already closed - flush existing " +
                                    "file for " + "thread [" + currentThreadId + "] -> " + currentFile);
            }


            BloomFilter<Long> valueIdBloomFilter = valueIdFilterSet.get(currentThreadId);
            BloomFilter<Integer> probeIdBloomFilter = probeIdFilterSet.get(currentThreadId);

            count.put(currentThreadId, new AtomicInteger(0));
            valueIdFilterSet.put(currentThreadId,
                    BloomFilterUtil.newBloomFilterForValues(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));
            probeIdFilterSet.put(currentThreadId,
                    BloomFilterUtil.newBloomFilterForProbes(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));

            UploadFile newLogFile = new UploadFile(currentFile, currentThreadId, valueIdBloomFilter, probeIdBloomFilter);
            fileList.offer(newLogFile);


        }

        if (shutdown) {
            return;
        }
        File nextFile = fileNameGenerator.getNextFile(String.valueOf(currentThreadId));
        currentFileMap.put(currentThreadId, nextFile.getPath());
        out = new BufferedOutputStream(Files.newOutputStream(nextFile.toPath()), WRITE_BYTE_BUFFER_SIZE);
        threadFileMap.put(currentThreadId, out);

        count.put(currentThreadId, new AtomicInteger(0));
        valueIdFilterSet.put(currentThreadId,
                BloomFilterUtil.newBloomFilterForValues(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));
        probeIdFilterSet.put(currentThreadId,
                BloomFilterUtil.newBloomFilterForProbes(BloomFilterUtil.BLOOM_FILTER_BIT_SIZE));
    }

    /**
     * Close the stream.
     */
    public void close() {
        for (Map.Entry<Integer, OutputStream> threadStreamEntrySet : threadFileMap.entrySet()) {
            OutputStream out = threadStreamEntrySet.getValue();
            int streamTheadId = threadStreamEntrySet.getKey();
            System.out.print("[videobug] close file for thread [" + streamTheadId + "]\n");
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

        try {


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
        int stringLength = stringObject.length();
        int bytesToWrite = 1 + 8 + 4 + stringLength;

        int currentThreadId = threadId.get();

        try {
            if (getThreadEventCount(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            errorLogger.log(e);
        }


        if (stringLength > 0) {
            fileCollector.indexStringEntry(id, stringObject);
        }


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

            TaskQueueArray[(int) (eventId % TASK_QUEUE_CAPACITY)] =
                    new OffLoadTaskPayload(currentThreadId, probeId, valueId);

            eventId++;
        } catch (IOException e) {
            errorLogger.log(e);
        }
//            System.err.println("Write new event - 4," + id + "," + value + " = " + this.bytesWritten);

    }

    public void writeNewTypeRecord(int typeId, String typeName, byte[] toString) {

        fileCollector.indexTypeEntry(typeId, typeName, toString);

    }

    public void writeWeaveInfo(byte[] byteArray) {
        fileCollector.addClassWeaveInfo(byteArray);
    }

    public void shutdown() throws IOException {
        System.err.println("[videobug] shutdown logger");
        skipUploads = true;
        shutdown = true;

        logFileTimeAgeChecker.shutdown();
        fileCollector.shutdown();
        threadPoolExecutor5Seconds.shutdown();
        threadPoolExecutor.shutdown();
    }

    /**
     * Block type: 7
     * Event with object value serialized
     *
     * @param probeId probe id
     * @param valueId value
     * @param toByteArray serialized object representation
     */
    @Override
    public void writeEvent(int probeId, long valueId, byte[] toByteArray) {
        long timestamp = currentTimestamp;
        int currentThreadId = threadId.get();

        try {

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);


            dos.write(7);
            dos.writeLong(eventId);
            dos.writeLong(timestamp);
            dos.writeInt(probeId);
            dos.writeLong(valueId);
            dos.writeInt(toByteArray.length);
            dos.write(toByteArray);


            getStreamForThread(currentThreadId).write(baos.toByteArray());

            TaskQueueArray[(int) (eventId % TASK_QUEUE_CAPACITY)] =
                    new OffLoadTaskPayload(currentThreadId, probeId, valueId);

            eventId++;
        } catch (IOException e) {
            errorLogger.log(e);
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
