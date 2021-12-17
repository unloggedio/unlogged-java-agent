package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.RSocket;
import io.rsocket.metadata.CompositeMetadataCodec;
import io.rsocket.metadata.RoutingMetadata;
import io.rsocket.metadata.TaggingMetadataCodec;
import io.rsocket.metadata.WellKnownMimeType;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class AggregatedLogger implements Runnable {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 10000000;
    public static final int BYTE_BUFFER_SIZE = 32 * 1024;
    /**
     * The data size of an event.
     */
    public static final int BYTES_PER_EVENT = 16;
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
    private String currentFile;
    private CompositeByteBuf dataEventMetadata;
    private int bytesWritten = 0;

    /**
     * Create an instance of stream.
     *
     * @param target  is an object generating file names.
     * @param logger  is to report errors that occur in this class.
     * @param rsocket
     */
    public AggregatedLogger(FileNameGenerator target, IErrorLogger logger, RSocket rsocket) {
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
        fileList.add(currentFile);
        out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(nextFile), BYTE_BUFFER_SIZE));
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
                if (bytesToWrite + bytesWritten > BYTE_BUFFER_SIZE) {
                    out.flush();
                    this.bytesWritten = 0;
                }

                this.bytesWritten += bytesToWrite;
                out.writeByte(1);
                out.writeLong(id);
                out.writeInt(typeId.length());
                out.writeBytes(typeId);
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
                if (bytesToWrite + bytesWritten > BYTE_BUFFER_SIZE) {
                    out.flush();
                    this.bytesWritten = 0;
                }

                this.bytesWritten += bytesToWrite;
                out.writeByte(2);
                out.writeLong(id);
                out.writeInt(stringObject.length());
                out.writeBytes(stringObject);
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
                if (bytesToWrite + bytesWritten > BYTE_BUFFER_SIZE) {
                    out.flush();
                    this.bytesWritten = 0;
                }

                this.bytesWritten += bytesToWrite;
                out.writeByte(3);
                out.writeInt(toString.length());
                out.writeBytes(toString);
                count++;
                // System.err.println("Write new exception - 3," + toString.length() + " - " + toString + " = " + this.bytesWritten);
            } catch (IOException e) {
                err.log(e);
            }
        }
    }

    public synchronized void writeEvent(int id, long value) {
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
                int bytesToWrite = 1 + 8 + 8;
                if (bytesToWrite + bytesWritten > BYTE_BUFFER_SIZE) {
                    out.flush();
                    this.bytesWritten = 0;
                }

                this.bytesWritten += bytesToWrite;
                out.writeByte(4);
                out.writeLong(id);
                out.writeLong(value);
                count++;
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
                if (bytesToWrite + bytesWritten > BYTE_BUFFER_SIZE) {
                    out.flush();
                    this.bytesWritten = 0;
                }
                this.bytesWritten += bytesToWrite;
                out.writeByte(5);
                out.writeInt(toString.length());
                out.writeBytes(toString);
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
        dataEventMetadata = ByteBufAllocator.DEFAULT.compositeBuffer();

        RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
                ByteBufAllocator.DEFAULT, Collections.singletonList("data-event")
        );
        CompositeMetadataCodec.encodeAndAddMetadata(dataEventMetadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                routingMetadata.getContent()
        );
    }

    @Override
    public void run() {
        System.out.println("Event file consumer started");
        while (true) {
            while (fileList.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    err.log(e);
                }
            }
            String fileToConsume = fileList.remove(0);
            try {
                File file = new File(fileToConsume);
                InputStream fileInputStream = new BufferedInputStream(new FileInputStream(file));
                // System.err.println("File opened - " + fileToConsume);
                int eventsRead = 0;
                while (eventsRead < MAX_EVENTS_PER_FILE) {
                    int availableBytes = 0;
                    while (availableBytes == 0) {
                        // System.err.println("No bytes yet, sleeping");
                        Thread.sleep(100);
                        availableBytes = fileInputStream.available();
                    }
                    byte[] fileBytes = new byte[1024 * 1024];
                    // System.err.println("Bytes found - " + availableBytes);
                    int bytesRead = fileInputStream.read(fileBytes);
                    if (bytesRead == 0) {
                        // System.err.println("Bytes read is 0");
                        continue;
                    }
                    // System.err.println("Bytes read is " + bytesRead);

                    ByteBuffer fileByteBuffer = ByteBuffer.wrap(fileBytes);

                    int bytesConsumed = 0;

                    while (bytesConsumed < bytesRead) {


                        int eventType = fileByteBuffer.get();
                        bytesConsumed++;
//                        // System.err.println("1 byte consumed, event type: " + eventType);
                        switch (eventType) {
                            case 1:
                                // new object
                                long id = fileByteBuffer.getLong();
                                String str = getNextString(fileByteBuffer);
                                bytesConsumed += 8 + 4 + str.length();
                                // System.err.println("1 - " + id + " - " + str);
                                break;
                            case 2:
                                // new string
                                long stringId = fileByteBuffer.getLong();
                                String string = getNextString(fileByteBuffer);
                                bytesConsumed += 8 + 4 + string.length();
                                // System.err.println("2 - " + stringId + " - " + string);
                                break;
                            case 3:
                                // new exception
                                long exceptionId = fileByteBuffer.getLong();
                                String exception = getNextString(fileByteBuffer);
                                bytesConsumed += 8 + 4 + exception.length();
                                // System.err.println("3 - " + exceptionId + " - " + exception);
                                break;
                            case 4:
                                // data event
                                long dataId = fileByteBuffer.getLong();
                                long value = fileByteBuffer.getLong();
                                bytesConsumed += 8 + 8;
                                // System.err.println("4 - " + dataId + " - " + value);
                                break;
                            case 5:
                                // type record
                                String type = getNextString(fileByteBuffer);
                                bytesConsumed += 4 + type.length();
                                // System.err.println("5 - " + type.length());
                                break;
                            case 6:
                                // weave info
                                byte[] weaveInfo = getNextBytes(fileByteBuffer);
                                bytesConsumed += 4 + weaveInfo.length;
                                // System.err.println("6 - " + weaveInfo.length);
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
                                // System.err.println("Invalid event type found in file: " + eventType);
                                System.exit(1);
                                throw new Exception("invalid event type in file, cannot process further");
                        }
                        eventsRead++;
                        // System.err.println("Consumed " + bytesConsumed + "/" + bytesRead + ": events = " + eventsRead);
                    }

                }
                boolean fileDeleted = file.delete();
                if (!fileDeleted) {
                    // System.err.println("Failed to delete file " + file.getAbsolutePath());
                }

            } catch (Exception e) {
                err.log(e);
            }
        }
    }


    private String getNextString(ByteBuffer buffer) {
        int stringLength = buffer.getInt();
//        // System.err.println("String length - " + stringLength);
        byte[] str = new byte[stringLength];
        buffer.get(str);
        return new String(str);
    }


    private byte[] getNextBytes(ByteBuffer buffer) {
        int stringLength = buffer.getInt();
//        // System.err.println("String length - " + stringLength);
        byte[] str = new byte[stringLength];
        buffer.get(str);
        return str;
    }

    public void writeClassInfo(String str) {

    }

    public void writeMethodInfo(String str) {

    }

    public void writeDataInfo(String str) {

    }

    public void writeWeaveInfo(byte[] toByteArray) {
        try {
            if (count >= MAX_EVENTS_PER_FILE) {
                prepareNextFile();
            }
            int bytesToWrite = 1 + 4 + toByteArray.length;
            if (bytesToWrite + bytesWritten > BYTE_BUFFER_SIZE) {
                out.flush();
                this.bytesWritten = 0;
            }
            this.bytesWritten += bytesToWrite;
            out.writeByte(6);
            out.writeInt(toByteArray.length);
            out.write(toByteArray);
            count++;
            // System.err.println("Write weave 6," + toByteArray.length + " = " + this.bytesWritten);
        } catch (IOException e) {
            err.log(e);
        }
    }
}
