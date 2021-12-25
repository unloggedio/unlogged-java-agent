package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.RSocket;
import io.rsocket.metadata.CompositeMetadataCodec;
import io.rsocket.metadata.RoutingMetadata;
import io.rsocket.metadata.TaggingMetadataCodec;
import io.rsocket.metadata.WellKnownMimeType;
import io.rsocket.util.DefaultPayload;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.sql.Time;
import java.time.Instant;
import java.util.*;
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
public class BinaryRsocketAggregatedLogger implements Runnable {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 100000 * 2;
    public static final int WRITE_BYTE_BUFFER_SIZE = 1024 * 1024;
    public static final int WRITE_BYTE_BUFFER_SIZE_BY_10 = WRITE_BYTE_BUFFER_SIZE / 10;
    public static final int MAX_LOG_FILE_SIZE = 32 * 1024 * 1024 * 10;
    /**
     * The data size of an event.
     */
    public static final int BYTES_PER_EVENT = 16;
    public static final int READ_BUFFER_SIZE = 1024 * 1024 * 32;
    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);
    /**
     * Assign an integer to this thread.
     */
    private static final ThreadLocal<Integer> threadId = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return nextThreadId.getAndIncrement();
        }
    };
    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024 * 4);
    private final byte[] FILE_BUFFER = new byte[READ_BUFFER_SIZE];
    private final BlockingQueue<String> fileList = new ArrayBlockingQueue<String>(1024);
    private final ReentrantLock lock = new ReentrantLock();
    private final String token;
    private RSocket rsocket;
    private FileNameGenerator files;
    private DataOutputStream out = null;
    private IErrorLogger err;
    private int count;
    private long eventId = 0;
    private String currentFile;
    private CompositeByteBuf dataEventMetadata;
    private int bytesWritten = 0;
    private int bytesRemaining = 0;
    private CompositeByteBuf classMapMetadata;
    private CompositeByteBuf typeMapMetadata;
    private CompositeByteBuf stringMapMetadata;
    private CompositeByteBuf variableMapMetadata;
    private Map<Integer, CompositeByteBuf> metadataMap;
    private final String sessionId;

    /**
     * Create an instance of stream.
     *
     * @param target is an object generating file names.
     * @param logger is to report errors that occur in this class.
     * @param token
     */
    public BinaryRsocketAggregatedLogger(FileNameGenerator target, IErrorLogger logger, String token, String sessionId) {
        this.token = token;
        this.sessionId = sessionId;
        System.err.println("Session Id: [" + sessionId + "]");
        try {
            files = target;
            err = logger;
            prepareMetadata();
            prepareNextFile();
            System.out.printf("Create aggregated logger -> %s\n", currentFile);
//            if (this.rsocket != null) {
//                System.out.println("Socket connected, creating aggregated log consumer");
            new Thread(this).start();
//            }
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
        out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE));
        out.writeBytes(sessionId);
        count = 0;
        this.bytesWritten = 0;
    }

    /**
     * Close the stream.
     */
    public synchronized void close() {
        System.out.print("Close file\n");
        try {
            out.close();
            out = null;
        } catch (IOException e) {
            out = null;
            err.log(e);
        }
    }

    public void writeNewObjectType(long id, String typeId) {
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 8 + 4 + typeId.length();
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }

            this.bytesWritten += bytesToWrite;
            out.writeByte(1);
            out.writeLong(id);
            writeString(typeId);
            count++;
            // System.err.println("Write new object - 1," + id + "," + typeId.length() + " - " + typeId + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
    }

    public void writeNewString(long id, String stringObject) {
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 8 + 4 + stringObject.length();
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
                this.bytesRemaining = WRITE_BYTE_BUFFER_SIZE;
            }

            this.bytesWritten += bytesToWrite;
            this.bytesRemaining -= bytesToWrite;
            out.writeByte(2);
            out.writeLong(id);
            writeString(stringObject);
            count++;
            // System.err.println("Write new string - 2," + id + "," + stringObject.length() + " - " + stringObject + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }

    }

    public void writeNewException(String toString) {
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 4 + toString.length();
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }

            this.bytesWritten += bytesToWrite;
            out.writeByte(3);
            writeString(toString);
            count++;
            // System.err.println("Write new exception - 3," + toString.length() + " - " + toString + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
    }

    private void writeString(String string) throws IOException {
        out.writeInt(string.length());
        out.writeBytes(string);
    }

    public synchronized void writeEvent(int id, long value) {
//        long timeStamp = System.nanoTime();
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 4 + 8 + 4 + 8;
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }

            this.bytesWritten += bytesToWrite;

            synchronized (this) {
                out.writeByte(4);
                out.writeInt(threadId.get());
                out.writeLong(eventId);
                out.writeInt(id);
                out.writeLong(value);
            }

            count++;
            eventId++;
//            System.err.println("Write new event - 4," + id + "," + value + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
    }

    public void writeNewTypeRecord(String toString) {
        try {
            lock.lock();
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 4 + toString.length();
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }
            this.bytesWritten += bytesToWrite;
            out.writeByte(5);
            writeString(toString);
            count++;
            // System.err.println("Write type record - 5," + toString.length() + " - " + toString + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
    }

    private void prepareMetadata() {
        metadataMap = new HashMap<>();

        metadataMap.put(1, createMetadata("object-mapping"));
        metadataMap.put(2, createMetadata("string-mapping"));
        metadataMap.put(3, createMetadata("exception-mapping"));
        metadataMap.put(4, createMetadata("data-event"));
        metadataMap.put(5, createMetadata("type-mapping"));
        metadataMap.put(6, createMetadata("class-mapping"));


    }

    private CompositeByteBuf createMetadata(String path) {
        CompositeByteBuf metadata = ByteBufAllocator.DEFAULT.compositeBuffer();
        RoutingMetadata route = TaggingMetadataCodec.createRoutingMetadata(
                ByteBufAllocator.DEFAULT, Collections.singletonList(path)
        );
        CompositeMetadataCodec.encodeAndAddMetadata(metadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                route.getContent()
        );
        return metadata;
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
        while (true) {
//            while (fileList.isEmpty()) {
//                try {
//                    System.err.println("File list is empty");
//                    Thread.sleep(3000);
//                } catch (InterruptedException e) {
//                    err.log(e);
//                }
//            }
            try {
                String filePath = fileList.take();
                System.err.println("File to upload: " + filePath);
                long start = System.currentTimeMillis();
                sendPOSTRequest("http://localhost:8080/checkpoint/upload", filePath);
                long end = System.currentTimeMillis();
                System.err.println("Upload took " + (end - start) / 1000 + " seconds");

            } catch (InterruptedException e) {
                System.err.println("Failed to upload file: " + e.getMessage());
                err.log(e);
            }
        }
    }

    public void run1() {
        System.out.println("Event file consumer started");
        DataOutputStream resetStream = null;
        ByteArrayOutputStream resetBufferStream = null;
        int reset = 0;
        ArrayDeque<Byte> byteQueue = new ArrayDeque<>(READ_BUFFER_SIZE);
        while (true) {
            while (fileList.isEmpty()) {
                try {
                    System.err.println("File list is empty " + fileList);
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    err.log(e);
                }
            }
            String fileToConsume = null;
            try {
                fileToConsume = fileList.take();
            } catch (InterruptedException e) {
                err.log(e);
                continue;
            }
            File file = null;
            long start = 0L;
            int bytesConsumedTotal = 0;
            int totalEventRead = 0;
            int eventsRead = 0;
            int bytesRead = 0;
            int bytesConsumed = 0;
            int eventType = -1;
//            DataOutputStream tempBuffer;
            ByteBuffer tempStream = null;
            try {
                start = System.currentTimeMillis();
                file = new File(fileToConsume);
                DataInputStream fileInputStream = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1024 * 1024));
                System.err.println("[" + Time.from(Instant.now()) + "] " + "File opened - " + fileToConsume);
                while (eventsRead < MAX_EVENTS_PER_FILE) {
                    Thread.sleep(900);
                    bytesConsumed = 0;

                    long bytesStart = System.currentTimeMillis();
                    while (true) {

                        if (eventsRead >= MAX_EVENTS_PER_FILE) {
                            break;
                        }

                        while (fileInputStream.available() == 0) {
                            System.err.println("0 bytes yet, sleeping, read only " + eventsRead + " of " + MAX_EVENTS_PER_FILE);
                            Thread.sleep(3000);
                        }

                        try {
                            eventType = fileInputStream.readByte();
                            bytesConsumed++;
//                            System.err.println("Event: " + eventType + " at byte [" + (bytesConsumedTotal + bytesConsumed - 1) + "]");
                            ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
                            totalEventRead++;
                            switch (eventType) {
                                case 1:
                                    // new object
                                    long id = fileInputStream.readLong();
                                    String str = getNextString(fileInputStream);
                                    bytesConsumed += 8 + 4 + str.length();


                                    buffer.writeLong(id);
                                    buffer.writeInt(str.length());
                                    buffer.writeBytes(str.getBytes());

                                    // System.err.println("1 - " + id + " - " + str);
                                    break;
                                case 2:
                                    // new string
                                    long stringId = fileInputStream.readLong();
                                    String string = getNextString(fileInputStream);
                                    bytesConsumed += 8 + 4 + string.length();

                                    buffer.writeLong(stringId);
                                    buffer.writeInt(string.length());
                                    buffer.writeBytes(string.getBytes());


                                    // System.err.println("2 - " + objectId + " - " + string);
                                    break;
                                case 3:
                                    // new exception
                                    String exception = getNextString(fileInputStream);
                                    bytesConsumed += 4 + exception.length();

                                    buffer.writeInt(exception.length());
                                    buffer.writeBytes(exception.getBytes());

                                    // System.err.println("3 - " + " - " + exception);
                                    break;
                                case 4:
                                    // data event
                                    int threadId = fileInputStream.readInt();
                                    long timestamp = fileInputStream.readLong();
                                    int dataId = fileInputStream.readInt();
                                    long value = fileInputStream.readLong();
                                    bytesConsumed += 4 + 8 + 4 + 8;

                                    buffer.writeInt(threadId);
                                    buffer.writeLong(timestamp);
                                    buffer.writeInt(dataId);
                                    buffer.writeLong(value);

                                    // System.err.println("4 - " + dataId + " - " + value);
                                    break;
                                case 5:
                                    // type record
                                    String type = getNextString(fileInputStream);

                                    bytesConsumed += 4 + type.length();
                                    // System.err.println("5 - " + type.length());

                                    buffer.writeInt(type.length());
                                    buffer.writeBytes(type.getBytes());

                                    break;
                                case 6:
                                    // weave info
                                    byte[] weaveInfo = getNextBytes(fileInputStream);

//                                if (weaveInfo.length + bytesConsumed > bytesRead> ) {
//
//                                }

                                    bytesConsumed += 4 + weaveInfo.length;
                                    // System.err.println("6 - " + weaveInfo.length + " - " + new String(weaveInfo));

//                                buffer.writeInt(weaveInfo.length);
                                    buffer.writeBytes(weaveInfo);
                                    break;
                                case 7:
                                    // method info
                                    // System.err.println("7 - ");
                                    break;
                                case 8:
                                    // data info
                                    // System.err.println("8 - ");
                                    break;
                                default:
                                    System.err.println("Invalid event type found in file: " + eventType + " at byte " + bytesConsumed);
//                                System.exit(1);
                                    throw new Exception("invalid event type in file, cannot process further at byte " + bytesConsumedTotal + " of file " + file.getAbsolutePath());
                            }

                            if (metadataMap.get(eventType) != null) {
                                this.rsocket.fireAndForget(DefaultPayload.create(buffer, metadataMap.get(eventType).copy())).subscribe();
                            }
                            eventsRead++;
                        } catch (EOFException e) {
                            System.err.println("Consumed " + bytesConsumed / (1024 * 1024) + "MBs : events = " + eventsRead);
                            if (eventsRead >= MAX_EVENTS_PER_FILE) {
                                break;
                            }
                            System.err.println("Continue reading file for more events: " + e);
                            err.log(e);
                        }
                    }
                    long bytesEnd = System.currentTimeMillis();
                    if (bytesEnd - bytesStart > 1000) {
                        System.err.println("[" + Time.from(Instant.now()) + "] " + "Consumed [" + bytesConsumed + "] at " + (bytesConsumed / 1024) / ((bytesEnd - bytesStart) / 1000) + " Kb/s");
                    }
                    bytesConsumedTotal += bytesConsumed;


                }
                System.err.println("Finished reading file " + file.getAbsolutePath() + " == " + bytesConsumedTotal / (1024 * 1024) + "MB");
//                if (!fileDeleted) {
                // System.err("Failed to delete file " + file.getAbsolutePath());
//                }

            } catch (Exception e) {
//                System.err.println("exception " + e.getMessage() + "[" + e.getStackTrace()[0].getFileName() + "]" + e.getStackTrace()[0].getLineNumber());
                e.printStackTrace();
                err.log(e);
            } finally {
                long end = System.currentTimeMillis();
                System.err.println("[" + Time.from(Instant.now()) + "] " + "Took " + ((end - start) / (1000)) + " seconds for reading file " + file.getAbsolutePath() + " - " + bytesConsumedTotal / 1000 + "Kb" + " => " + eventId);
                if (file != null) {
                    file.delete();
                }
            }
        }
    }


    private String getNextString(DataInputStream buffer) throws IOException {
        int stringLength = buffer.readInt();
// System.err.println("String length - " + stringLength);
        byte[] str = new byte[stringLength];
        buffer.readFully(str);
        return new String(str);
    }


    private byte[] getNextBytes(DataInputStream buffer) throws IOException {
        int stringLength = buffer.readInt();
// System.err.println("String length - " + stringLength);
        byte[] str = new byte[stringLength];
        buffer.readFully(str);
        return str;
    }

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
            out.writeByte(6);
            out.writeInt(byteArray.length);
            out.write(byteArray);
            count++;
            // System.err.println("Write weave 6," + byteArray.length + " - " + new String(byteArray) + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        } finally {
            lock.unlock();
        }
    }
}
