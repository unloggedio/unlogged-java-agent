package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.sql.Time;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class BinaryFileAggregatedLogger implements Runnable, AggregatedFileLogger {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 100000 * 5;
    public static final int WRITE_BYTE_BUFFER_SIZE = 1024 * 1024;
    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);
    /**
     * Assign an integer to this thread.
     */
    private static final ThreadLocal<Integer> threadId = ThreadLocal.withInitial(nextThreadId::getAndIncrement);
    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    private final BlockingQueue<String> fileList = new ArrayBlockingQueue<String>(1024);
    private final ReentrantLock lock = new ReentrantLock();
    private final String token;
    private final String serverEndpoint;
    private final String sessionId;
    private String hostname;
    private FileNameGenerator files;
    private BufferedOutputStream out = null;
    private IErrorLogger err;
    private int count;
    private long eventId = 0;
    private String currentFile;
    private int bytesWritten = 0;
    private long timestamp = System.currentTimeMillis();

    /**
     * Create an instance of stream.
     *
     * @param outputDirName location for generated files
     * @param logger        is to report errors that occur in this class.
     * @param token
     * @param serverAddress
     */
    public BinaryFileAggregatedLogger(String outputDirName, IErrorLogger logger, String token, String sessionId, String serverAddress) {
        this.token = token;
        this.sessionId = sessionId;
        this.serverEndpoint = serverAddress;
        try {
            this.hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream()));
                this.hostname = reader.readLine();
                reader.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        System.err.println("Session Id: [" + sessionId + "] on hostname [" + hostname + "]");
        try {
            files = new FileNameGenerator(new File(outputDirName), "log-", ".selog");
            err = logger;
            prepareNextFile();
            writeHostname();
            writeTimestamp();
            System.out.printf("Create aggregated logger -> %s\n", currentFile);
            if (this.serverEndpoint != null) {
                new Thread(this).start();
                new Thread(new LogFileTimeExpiry()).start();

            }
            count = 0;
        } catch (IOException e) {
            err.log(e);
        }
    }

    private void prepareNextFile() throws IOException {
        if (out != null) {
            try {
                out.flush();
                out.close();
                fileList.add(currentFile);
            } catch (IOException e) {
                err.log(e);
            }
        }
        File nextFile = files.getNextFile();
        currentFile = nextFile.getAbsolutePath();
        System.err.println("[" + Time.from(Instant.now()) + "] Prepare next file: " + currentFile);
        out = new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE);
        out.write(sessionId.getBytes());
        count = 0;
        this.bytesWritten = 0;
    }

    /**
     * Close the stream.
     */
    public void close() {
        System.out.print("Close file\n");
        try {
            out.close();
            out = null;
        } catch (IOException e) {
            out = null;
            err.log(e);
        }
    }

    @Override
    public void writeNewObjectType(long id, long typeId) {
        int bytesToWrite = 1 + 8 + 8;
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
        this.bytesWritten += bytesToWrite;

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
        count++;
        // System.err.println("Write new object - 1," + id + "," + typeId.length() + " - " + typeId + " = " + this.bytesWritten);

    }

    @Override
    public void writeNewString(long id, String stringObject) {
        int bytesToWrite = 1 + 8 + 4 + stringObject.length();
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }


        this.bytesWritten += bytesToWrite;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(2);
            tempOut.writeLong(id);
            tempOut.writeInt(stringObject.length());
            tempOut.write(stringObject.getBytes());
            out.write(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
//        writeString(stringObject);

        count++;

        // System.err.println("Write new string - 2," + id + "," + stringObject.length() + " - " + stringObject + " = " + this.bytesWritten);


    }

    @Override
    public void writeNewException(byte[] exceptionBytes) {
        int bytesToWrite = 1 + 4 + exceptionBytes.length;
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
        this.bytesWritten += bytesToWrite;


        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(3);
            tempOut.writeInt(exceptionBytes.length);
            tempOut.write(exceptionBytes);
            out.write(baos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
//        writeString(toString);
        count++;
        // System.err.println("Write new exception - 3," + toString.length() + " - " + toString + " = " + this.bytesWritten);
    }

    @Override
    public void writeEvent(int id, long value) {

        int bytesToWrite = 1 + 4 + 8 + 4 + 8;


        try {

            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }

        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }

        this.bytesWritten += bytesToWrite;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(4);          // 1
            tempOut.writeInt(threadId.get()); // 4
            tempOut.writeLong(eventId);       // 8
            tempOut.writeInt(id);             // 4
            tempOut.writeLong(value);         // 8

            this.out.write(baos.toByteArray());
            count++;
            eventId++;
        } catch (IOException e) {
            err.log(e);
        }
//            System.err.println("Write new event - 4," + id + "," + value + " = " + this.bytesWritten);

    }

    @Override
    public void writeHostname() {
        try {
            int bytesToWrite = 1 + 4 + hostname.length();
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }
            this.bytesWritten += bytesToWrite;

            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);


            tempOut.writeByte(8);
            tempOut.writeInt(hostname.length());
            tempOut.writeBytes(hostname);
            out.write(baos.toByteArray());
            count++;

        } catch (IOException e) {
            err.log(e);
        }
    }

    @Override
    public void writeTimestamp() {
        int bytesToWrite = 1 + 8;
        long timeStamp = System.currentTimeMillis();
        if (count < 0) {
            return;
        }


        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }

        this.bytesWritten += bytesToWrite;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(7);      // 1
            tempOut.writeLong(timeStamp); // 8
            out.write(baos.toByteArray());
        } catch (IOException e) {
            err.log(e);
        }

    }

    @Override
    public void writeNewTypeRecord(String toString) {

        int bytesToWrite = 1 + 4 + toString.length();

        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }

        this.bytesWritten += bytesToWrite;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);
            tempOut.writeByte(5);              // 1
            tempOut.writeInt(toString.length());  // 4
            tempOut.write(toString.getBytes());   // length
            out.write(baos.toByteArray());
        } catch (IOException e) {
            err.log(e);
            e.printStackTrace();
        }
//        writeString(toString);
        count++;
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
                new File(filePath).delete();

            } catch (InterruptedException e) {
                System.err.println("Failed to upload file: " + e.getMessage());
                err.log(e);
            }
        }
    }


    @Override
    public void writeWeaveInfo(byte[] byteArray) {
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 4 + byteArray.length;
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                synchronized (this) {
                    out.flush();
                }
                this.bytesWritten = 0;
            }
//            System.err.println("Writing Event [" + 6 + "] at byte " + this.bytesWritten);
            this.bytesWritten += bytesToWrite;

            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytesToWrite);
            DataOutputStream tempOut = new DataOutputStream(baos);


            tempOut.writeByte(6);
            tempOut.writeInt(byteArray.length);
            tempOut.write(byteArray);
            out.write(baos.toByteArray());
            count++;
            // System.err.println("Write weave 6," + byteArray.length + " - " + new String(byteArray) + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
    }

    class LogFileTimeExpiry implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(1 * 1000);
//                    System.err.println("30 seconds log file checker");
                    timestamp = System.currentTimeMillis();
                    writeTimestamp();
                    lock.lock();
                    if (count > 0 && fileList.isEmpty()) {
                        System.err.println("1 seconds log file checker: " + count + " events in file");
                        prepareNextFile();
                    } else {
//                        System.err.println("30 seconds log file checker: not enough data");
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

}
