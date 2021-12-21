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
import java.nio.ByteBuffer;
import java.sql.Time;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class StringAppendAggregatedLogger implements Runnable {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 100000 * 1000;
    public static final int WRITE_BYTE_BUFFER_SIZE = 8 * 1024;
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
    public final ArrayList<Byte> data = new ArrayList<>(1024 * 1024);
    private final int mode = 0;
    private final List<String> fileList = new LinkedList<>();
    private final RSocket rsocket;
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

    /**
     * Create an instance of stream.
     *
     * @param target  is an object generating file names.
     * @param logger  is to report errors that occur in this class.
     * @param rsocket
     */
    public StringAppendAggregatedLogger(FileNameGenerator target, IErrorLogger logger, RSocket rsocket) {
        this.rsocket = rsocket;
        try {
            files = target;
            err = logger;
            prepareMetadata();
            prepareNextFile();
            System.out.printf("Create aggregated logger -> %s\n", currentFile);
            if (this.rsocket != null) {
                System.out.println("Socket connected, creating aggregated log consumer");
                new Thread(this).start();
            }
            count = 0;
        } catch (IOException e) {
            err.log(e);
        }
    }

    private void prepareNextFile() throws FileNotFoundException {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                err.log(e);
            }
        }
        File nextFile = files.getNextFile();
        currentFile = nextFile.getAbsolutePath();
        System.err.println("[" + Time.from(Instant.now()) + "] Prepare next file: " + currentFile);
        fileList.add(currentFile);
        out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(nextFile), WRITE_BYTE_BUFFER_SIZE));
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

    public synchronized void writeNewObjectType(long id, String typeId) {
        if (mode == 1) {
            record(String.format("1,%s,%s\n", id, typeId));
        } else if (mode == 2) {
            String sb = "1" + id +
                    "," +
                    typeId + "\n";
            record(sb);
        } else if (mode == 3) {
            String sb = new StringBuilder().append("1").append(id).append(",").append(typeId).append("\n").toString();
            record(sb);
        } else if (mode == 0) {
            try {
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
            }
        }
    }

    public synchronized void writeNewString(long id, String stringObject) {
        if (mode == 1) {
            record(String.format("2,%s,%s\n", id, stringObject));
        } else if (mode == 2) {
            String sb = "2" + "," +
                    id +
                    "," +
                    stringObject + "\n";
            record(sb);
        } else if (mode == 3) {
            String sb = new StringBuilder().append("2").append(",").append(id).append(",").append(stringObject).append("\n").toString();
            record(sb);
        } else if (mode == 0) {
            try {
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
            }
        }

    }

    public synchronized void writeNewException(String toString) {
        if (mode == 1) {
            record(String.format("3,%s\n", toString));
        } else if (mode == 2) {
            String sb = "3" + "," +
                    toString + "\n";
            record(sb);
        } else if (mode == 3) {
            String sb = new StringBuilder().append("3").append(",").append(toString).append("\n").toString();
            record(sb);
        } else if (mode == 0) {
            try {
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
            }
        }
    }

    private synchronized void writeString(String string) throws IOException {
        out.writeInt(string.length());
        out.writeBytes(string);
    }

    public synchronized void writeEvent(int id, long value) {
//        long timeStamp = System.nanoTime();
        if (mode == 1) {
            record(String.format("4,%s,%s\n", id, value));
        } else if (mode == 2) {
            String sb = "4" + "," +
                    id +
                    "," +
                    value + "\n";
            record(sb);
        } else if (mode == 3) {
            String sb = new StringBuilder().append("4").append(",").append(id).append(",").append(value).append("\n").toString();
            record(sb);
        } else if (mode == 0) {
            try {
                if (count >= MAX_EVENTS_PER_FILE) {
                    prepareNextFile();
                }
                int bytesToWrite = 1 + 4 + 8 + 4 + 8;
                if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                    out.flush();
                    this.bytesWritten = 0;
                }

                this.bytesWritten += bytesToWrite;
                out.writeByte(4);
                out.writeInt(threadId.get());
                out.writeLong(eventId);
                out.writeInt(id);
                out.writeLong(value);
                count++;
                eventId++;
                // System.err.println("Write new event - 4," + id + "," + value + " = " + this.bytesWritten);
            } catch (IOException e) {
                err.log(e);
            }
        }
    }

    public synchronized void writeNewTypeRecord(String toString) {
        if (mode == 1) {
            record(String.format("5,%s\n", toString));
        } else if (mode == 2) {
            String sb = "5" + "," +
                    toString + "\n";
            record(sb);
        } else if (mode == 3) {
            String sb = new StringBuilder().append("5").append(",").append(toString).append("\n").toString();
            record(sb);
        } else if (mode == 0) {
            try {
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
            }
        }
    }

    private void record(String string) {
        try {
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            // System.err.println("Record called with - " + string);
            out.writeBytes(string);
            count++;
        } catch (IOException e) {
            err.log(e);
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

    @Override
    public void run() {
        System.out.println("Event file consumer started");
        while (true) {
            while (fileList.isEmpty()) {
                try {
                    System.err.println("File list is empty " + fileList);
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    err.log(e);
                }
            }
            String fileToConsume = fileList.remove(0);
            try {
                File file = new File(fileToConsume);
                InputStream fileInputStream = new BufferedInputStream(new FileInputStream(file));
                System.err.println("File opened - " + fileToConsume);
                int eventsRead = 0;
                int bytesConsumedTotal = 0;
                while (eventsRead < MAX_EVENTS_PER_FILE) {
                    int availableBytes = 0;

                    Thread.sleep(900);

                    while (availableBytes < WRITE_BYTE_BUFFER_SIZE) {
                        System.err.println(availableBytes + " bytes yet, sleeping");
                        Thread.sleep(3000);
                        availableBytes = fileInputStream.available();
                    }
                    byte[] fileBytes = new byte[READ_BUFFER_SIZE];
                    // System.err("Bytes found - [" + availableBytes + "] at [" + bytesConsumedTotal + "]");
                    int bytesRead = fileInputStream.read(fileBytes);
                    if (bytesRead == 0) {
                        System.err.println("Bytes read is 0");
                        continue;
                    }
//                    System.err.println("Bytes read is " + bytesRead + " / " + fileInputStream.available() + "[" + READ_BUFFER_SIZE + "]");

                    ByteBuffer fileByteBuffer = ByteBuffer.wrap(fileBytes);

                    int bytesConsumed = 0;

                    while (bytesConsumed < bytesRead) {


                        int eventType = fileByteBuffer.get();
                        bytesConsumed++;
                        System.err.println("Event: " + eventType + " at byte [" + (bytesConsumedTotal + bytesConsumed - 1) + "]");
                        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
                        switch (eventType) {
                            case 1:
                                // new object
                                long id = fileByteBuffer.getLong();
                                String str = getNextString(fileByteBuffer);
                                bytesConsumed += 8 + 4 + str.length();


                                buffer.writeLong(id);
                                buffer.writeInt(str.length());
                                buffer.writeBytes(str.getBytes());

                                // System.err.println("1 - " + id + " - " + str);
                                break;
                            case 2:
                                // new string
                                long stringId = fileByteBuffer.getLong();
                                String string = getNextString(fileByteBuffer);
                                bytesConsumed += 8 + 4 + string.length();

                                buffer.writeLong(stringId);
                                buffer.writeInt(string.length());
                                buffer.writeBytes(string.getBytes());


                                // System.err.println("2 - " + stringId + " - " + string);
                                break;
                            case 3:
                                // new exception
                                String exception = getNextString(fileByteBuffer);
                                bytesConsumed += 4 + exception.length();

                                buffer.writeInt(exception.length());
                                buffer.writeBytes(exception.getBytes());

                                // System.err.println("3 - " + " - " + exception);
                                break;
                            case 4:
                                // data event
                                int threadId = fileByteBuffer.getInt();
                                long timestamp = fileByteBuffer.getLong();
                                int dataId = fileByteBuffer.getInt();
                                long value = fileByteBuffer.getLong();
                                bytesConsumed += 4 + 8 + 4 + 8;

                                buffer.writeInt(threadId);
                                buffer.writeLong(timestamp);
                                buffer.writeInt(dataId);
                                buffer.writeLong(value);

                                // System.err.println("4 - " + dataId + " - " + value);
                                break;
                            case 5:
                                // type record
                                String type = getNextString(fileByteBuffer);

                                bytesConsumed += 4 + type.length();
                                // System.err.println("5 - " + type.length());

                                buffer.writeInt(type.length());
                                buffer.writeBytes(type.getBytes());

                                break;
                            case 6:
                                // weave info
                                byte[] weaveInfo = getNextBytes(fileByteBuffer);

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
//                                throw new Exception("invalid event type in file, cannot process further");
                        }

                        this.rsocket.fireAndForget(DefaultPayload.create(buffer, metadataMap.get(eventType).copy())).subscribe();
                        eventsRead++;
//                        System.err.println("Consumed " + bytesConsumed + "/" + bytesRead + ": events = " + eventsRead + " after [" + bytesConsumedTotal + "]");
                    }
                    bytesConsumedTotal += bytesConsumed;

                }
                System.err.println("Finished reading file " + file.getAbsolutePath());
                file.deleteOnExit();
//                if (!fileDeleted) {
                // System.err("Failed to delete file " + file.getAbsolutePath());
//                }

            } catch (Exception e) {
//                System.err.println("exception " + e.getMessage() + "[" + e.getStackTrace()[0].getFileName() + "]" + e.getStackTrace()[0].getLineNumber());
                e.printStackTrace();
                err.log(e);
            }
        }
    }


    private String getNextString(ByteBuffer buffer) {
        int stringLength = buffer.getInt();
// System.err.println("String length - " + stringLength);
        byte[] str = new byte[stringLength];
        buffer.get(str);
        return new String(str);
    }


    private byte[] getNextBytes(ByteBuffer buffer) {
        int stringLength = buffer.getInt();
// System.err.println("String length - " + stringLength);
        byte[] str = new byte[stringLength];
        buffer.get(str);
        return str;
    }

    public synchronized void writeWeaveInfo(byte[] byteArray) {
        try {
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 4 + byteArray.length;
            if (bytesToWrite + bytesWritten > WRITE_BYTE_BUFFER_SIZE) {
                out.flush();
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
        }
    }
}
