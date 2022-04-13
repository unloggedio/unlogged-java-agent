package com.videobug.agent.logging.util;

import com.videobug.agent.logging.IErrorLogger;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
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
    private static final ThreadLocal<Long> threadId = ThreadLocal.withInitial(Thread.currentThread()::getId);
    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    private final BlockingQueue<String> fileList = new ArrayBlockingQueue<String>(1024);
    private final ReentrantLock lock = new ReentrantLock();

    private final String sessionId;
    private final NetworkClient networkClient;
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
        this.sessionId = sessionId;
        this.networkClient = new NetworkClient(serverAddress, sessionId, token, logger);
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
            if (serverAddress != null) {
                new Thread(this).start();
//                new Thread(new LogFileTimeExpiry()).start();

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
//        System.err.println("[" + Time.from(Instant.now()) + "] Prepare next file: " + currentFile);
        out = new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE);

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

        int bytesToWrite = 1 + 8 + 8 + 4 + 8;


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
            tempOut.writeLong(threadId.get()); // 4
            tempOut.writeLong(System.currentTimeMillis());       // 8
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
    public void writeNewTypeRecord(int typeId, String typeName, String toString) {

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


    @Override
    public void run() {
        System.err.println("Sending dumps to: " + this.networkClient.getServerUrl());
        while (true) {
            try {
                String filePath = fileList.take();
                networkClient.uploadFile(filePath, 0);
//                new File(filePath).delete();
            } catch (InterruptedException | IOException e) {
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
                    if (count > 0) {
//                        System.err.println("1 seconds log file checker: " + count + " events in file");
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
