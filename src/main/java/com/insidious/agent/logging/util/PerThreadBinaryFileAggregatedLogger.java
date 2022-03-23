package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.sql.Time;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class PerThreadBinaryFileAggregatedLogger implements Runnable, AggregatedFileLogger {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 1000000;
    public static final int WRITE_BYTE_BUFFER_SIZE = 1024 * 1024;
    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);
    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    /**
     * Assign an integer to this thread.
     */
    private final ThreadLocal<Integer> threadId = ThreadLocal.withInitial(nextThreadId::getAndIncrement);
    private final BlockingQueue<String> fileList = new ArrayBlockingQueue<String>(1024);
    private final String token;
    private final String serverEndpoint;
    private final String sessionId;
    private final Map<Integer, BufferedOutputStream> threadFileMap = new HashMap<>();
    private final Map<Integer, String> currentFileMap = new HashMap<>();
    private final Map<Integer, AtomicInteger> count = new HashMap<>();
    private String hostname;
    private FileNameGenerator fileNameGenerator;
    private IErrorLogger err;
    private long eventId = 0;

    /**
     * Create an instance of stream.
     *
     * @param outputDirName location for generated files
     * @param logger        is to report errors that occur in this class.
     * @param token
     * @param serverAddress
     */
    public PerThreadBinaryFileAggregatedLogger(
            String outputDirName, IErrorLogger logger,
            String token, String sessionId, String serverAddress) {
        this.token = token;
        this.sessionId = sessionId;
        this.serverEndpoint = serverAddress;
        try {
            this.hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                Runtime.getRuntime().exec("hostname").getInputStream()
                        ));
                this.hostname = reader.readLine();
                reader.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }


        System.err.println("Session Id: [" + sessionId + "] on hostname [" + hostname + "]");
        try {
            fileNameGenerator = new FileNameGenerator(new File(outputDirName), "log-", ".selog");
            err = logger;

            for (int i = 0; i < 40; i++) {
                count.put(i, new AtomicInteger(0));
                prepareNextFile(i);
            }

            writeHostname();
            writeTimestamp();
            System.out.printf("Create aggregated logger -> %s\n", currentFileMap.get(-1));
            if (this.serverEndpoint != null) {
                new Thread(this).start();
                new Thread(new LogFileTimeExpiry()).start();

            }
        } catch (IOException e) {
            err.log(e);
        }
    }

    private BufferedOutputStream getStreamForThread(int threadId) {
        if (threadFileMap.containsKey(threadId)) {
            return threadFileMap.get(threadId);
        }
        BufferedOutputStream threadStream = null;
        try {
            threadStream = prepareNextFile(threadId);
        } catch (IOException e) {
            err.log(e);
        }
        return threadStream;
    }


    private synchronized BufferedOutputStream prepareNextFile(int currentThreadId) throws IOException {
        err.log("prepare next file for thread [" + currentThreadId + "]");


        if (count.containsKey(currentThreadId) && threadFileMap.get(currentThreadId) != null) {
            if (count.get(currentThreadId).get() < 10) {
                return threadFileMap.get(currentThreadId);
            }
        }

        BufferedOutputStream out = threadFileMap.get(currentThreadId);
        if (out != null) {
            try {
                err.log("flush existing file for thread [" + currentThreadId + "] -> " + currentFileMap.get(currentThreadId));
                out.flush();
                out.close();
                fileList.add(currentFileMap.get(currentThreadId));
            } catch (IOException e) {
                err.log(e);
            }
        }
        File nextFile = fileNameGenerator.getNextFile(currentThreadId);
        System.err.println("[" + Time.from(Instant.now()) + "] Prepare next file for thread [" + currentThreadId + "]: " + nextFile.getAbsolutePath());

        currentFileMap.put(currentThreadId, nextFile.getAbsolutePath());


        out = new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE);
        out.write(sessionId.getBytes());
        threadFileMap.put(currentThreadId, out);
        count.put(currentThreadId, new AtomicInteger(0));
        return out;
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
                err.log(e);
            }
        }


    }

    public void writeNewObjectType(long id, long typeId) {

        int currentThreadId = threadId.get();

        BufferedOutputStream out = getStreamForThread(currentThreadId);
        int bytesToWrite = 1 + 8 + 8;
        try {
            if (count.get(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            err.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(1);
            tempOut.writeLong(id);
            tempOut.writeLong(typeId);
//            writeString(typeId);
            out.write(baos.toByteArray());
        } catch (IOException e) {
            err.log(e);
        }
        count.get(currentThreadId).addAndGet(1);
        // System.err.println("Write new object - 1," + id + "," + typeId.length() + " - " + typeId + " = " + this.bytesWritten);

    }

    public void writeNewString(long id, String stringObject) {
        int bytesToWrite = 1 + 8 + 4 + stringObject.length();

        int currentThreadId = threadId.get();

        try {
            if (count.get(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            err.log(e);
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
        } catch (IOException e) {
            err.log(e);
        }
//        writeString(stringObject);

        count.get(currentThreadId).addAndGet(1);

        // System.err.println("Write new string - 2," + id + "," + stringObject.length() + " - " + stringObject + " = " + this.bytesWritten);


    }

    public void writeNewException(byte[] exceptionBytes) {
        int bytesToWrite = 1 + 4 + exceptionBytes.length;

        int currentThreadId = threadId.get();

        try {
            if (count.get(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            err.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(3);
            tempOut.writeInt(exceptionBytes.length);
            tempOut.write(exceptionBytes);
            getStreamForThread(threadId.get()).write(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
//        writeString(toString);
        count.get(currentThreadId).addAndGet(1);
        // System.err.println("Write new exception - 3," + toString.length() + " - " + toString + " = " + this.bytesWritten);
    }

    public void writeEvent(int id, long value) {

        int bytesToWrite = 1 + 4 + 8 + 4 + 8;
        long timestamp = System.currentTimeMillis();

        int currentThreadId = threadId.get();

        try {


            int currentEventCount = count.get(currentThreadId).get();
            if (currentEventCount >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }

        } catch (IOException e) {
            err.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(4);          // 1
            tempOut.writeLong(currentThreadId); // 8
            tempOut.writeLong(timestamp);       // 8
            tempOut.writeInt(id);             // 4
            tempOut.writeLong(value);         // 8

            getStreamForThread(threadId.get()).write(baos.toByteArray());
            count.get(currentThreadId).addAndGet(1);
            eventId++;
        } catch (IOException e) {
            err.log(e);
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
            err.log(e);
        }
    }

    public void writeTimestamp() {
        int bytesToWrite = 1 + 8;
        long timeStamp = System.currentTimeMillis();


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(7);      // 1
            tempOut.writeLong(timeStamp); // 8
            getStreamForThread(threadId.get()).write(baos.toByteArray());
        } catch (IOException e) {
            err.log(e);
        }

    }

    public void writeNewTypeRecord(String toString) {

        int bytesToWrite = 1 + 4 + toString.length();
        int currentThreadId = threadId.get();

        try {

            if (count.get(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
        } catch (IOException e) {
            err.log(e);
        }


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(5);              // 1
            tempOut.writeInt(toString.length());  // 4
            tempOut.write(toString.getBytes());   // length
            getStreamForThread(currentThreadId).write(baos.toByteArray());
        } catch (IOException e) {
            err.log(e);
            e.printStackTrace();
        }
//        writeString(toString);
        count.get(currentThreadId).addAndGet(1);
        // System.err.println("Write type record - 5," + toString.length() + " - " + toString + " = " + this.bytesWritten);
    }

    private void sendPOSTRequest(String url, String attachmentFilePath) {
        String charset = "UTF-8";
        File binaryFile = new File(attachmentFilePath);
        String boundary = "------------------------" + Long.toHexString(System.currentTimeMillis()); // Just generate some unique random value.
        String CRLF = "\r\n"; // Line separator required by multipart/form-data.
        int responseCode = 0;

        try {
            //Set POST general headers along with the boundary string (the seperator string of each part)
            URLConnection connection = new URL(url).openConnection();
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.addRequestProperty("User-Agent", "insidious/1.0.0");
            connection.addRequestProperty("Accept", "*/*");
            connection.addRequestProperty("Authorization", "Bearer " + this.token);

            OutputStream output = connection.getOutputStream();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, charset), true);

            // Send binary file - part
            // Part header
            writer.append("--" + boundary).append(CRLF);
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"" + binaryFile.getName() + "\"").append(CRLF);
            writer.append("Content-Type: application/octet-stream").append(CRLF);// + URLConnection.guessContentTypeFromName(binaryFile.getName())).append(CRLF);
            writer.append(CRLF).flush();

            // File data
            Files.copy(binaryFile.toPath(), output);
            output.flush();

            // End of multipart/form-data.
            writer.append(CRLF).append("--" + boundary + "--").flush();

            responseCode = ((HttpURLConnection) connection).getResponseCode();
            System.err.println("File uploaded: " + responseCode);

        } catch (Exception e) {
            e.printStackTrace();
            err.log(e);
        }

    }

    @Override
    public void run() {
        System.err.println("Sending dumps to: " + this.serverEndpoint);
        while (true) {

            try {
                String filePath = fileList.take();
                System.err.println("File to upload: " + filePath);
                long start = System.currentTimeMillis();
                sendPOSTRequest(this.serverEndpoint + "/checkpoint/upload", filePath);
                long end = System.currentTimeMillis();
                System.err.println("Upload took " + (end - start) / 1000 + " seconds, deleting file " + filePath);
//                new File(filePath).delete();

            } catch (InterruptedException e) {
                System.err.println("Failed to upload file: " + e.getMessage());
                err.log(e);
            }
        }
    }


    public void writeWeaveInfo(byte[] byteArray) {
        int currentThreadId = threadId.get();
        try {

            if (count.get(currentThreadId).get() >= MAX_EVENTS_PER_FILE) {
                prepareNextFile(currentThreadId);
            }
            int bytesToWrite = 1 + 4 + byteArray.length;


            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);


            tempOut.writeByte(6);
            tempOut.writeInt(byteArray.length);
            tempOut.write(byteArray);
            getStreamForThread(currentThreadId).write(baos.toByteArray());
            count.get(currentThreadId).addAndGet(1);
            // System.err.println("Write weave 6," + byteArray.length + " - " + new String(byteArray) + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        }

    }

    class LogFileTimeExpiry implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(2 * 1000);

                    for (Integer theThreadId : threadFileMap.keySet()) {
                        err.log("2 seconds log file checker: [ "
                                + theThreadId + " / " + threadFileMap.size()
                                + " ] threads open");
                        int eventCount = count.get(theThreadId).get();
                        if (eventCount > 0) {
                            err.log("log file for thread [" + theThreadId + "] has : " + eventCount + " events in file for thread ["
                                    + theThreadId + "] in file [" + currentFileMap.get(theThreadId) + "]");
                            prepareNextFile(theThreadId);
                        }
                    }
                } catch (InterruptedException | IOException e) {
                    err.log(e);
                }
            }
        }
    }

}
