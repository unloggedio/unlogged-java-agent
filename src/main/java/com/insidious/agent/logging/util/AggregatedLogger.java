package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
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
    private FileNameGenerator files;
    private DataOutputStream out = null;
    private IErrorLogger err;
    private int count;
    private String currentFile;
    private CompositeByteBuf dataEventMetadata;

    /**
     * Create an instance of stream.
     *
     * @param target is an object generating file names.
     * @param logger is to report errors that occur in this class.
     */
    public AggregatedLogger(FileNameGenerator target, IErrorLogger logger) {
        try {
            files = target;
            err = logger;
            prepareMetadata();
            prepareNextFile();
            System.out.printf("Create aggregated logger -> %s\n", currentFile);
            new Thread(this).start();
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
        out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(nextFile), 32 * 1024));
        count = 0;
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
                out.writeInt(1);
                out.writeLong(id);
                out.writeInt(typeId.length());
                out.writeBytes(typeId);
                count++;
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
                out.writeInt(2);
                out.writeLong(id);
                out.writeInt(stringObject.length());
                out.writeBytes(stringObject);
                count++;
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
                out.writeInt(3);
                out.writeInt(toString.length());
                out.writeBytes(toString);
                count++;
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
                out.writeInt(4);
                out.writeLong(id);
                out.writeLong(value);
                count++;
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
                out.writeInt(5);
                out.writeInt(toString.length());
                out.writeBytes(toString);
                count++;
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
                System.err.println("File opened - " + fileToConsume);
                int eventsRead = 0;
                while (eventsRead < MAX_EVENTS_PER_FILE) {
                    int availableBytes = 0;
                    while (availableBytes == 0) {
                        System.err.println("No bytes yet, sleeping");
                        Thread.sleep(100);
                        availableBytes = fileInputStream.available();
                    }
                    byte[] fileBytes = new byte[1024 * 1024];
                    System.err.println("Bytes found - " + availableBytes);
                    int bytesRead = fileInputStream.read(fileBytes);
                    if (bytesRead == 0) {
                        System.err.println("Bytes read is 0");
                        continue;
                    }
                    System.err.println("Bytes read is " + bytesRead);

                    ByteBuffer fileByteBuffer = ByteBuffer.wrap(fileBytes);

                    int bytesConsumed = 0;

                    while (bytesConsumed < bytesRead) {


                        int eventType = fileByteBuffer.getInt();
                        bytesConsumed++;
                        switch (eventType) {
                            case 1:
                                // new object
                                long id = fileByteBuffer.getLong();
                                String str = getNextString(fileByteBuffer);
                                bytesConsumed += 2 + str.length();
                                System.err.println("1 - " + id + " - " + str);
                                break;
                            case 2:
                                // new string
                                long stringId = fileByteBuffer.getLong();
                                String string = getNextString(fileByteBuffer);
                                bytesConsumed += 2 + string.length();
                                System.err.println("2 - " + stringId + " - " + string);
                                break;
                            case 3:
                                // new exception
                                long exceptionId = fileByteBuffer.getLong();
                                String exception = getNextString(fileByteBuffer);
                                bytesConsumed += 2 + exception.length();
                                System.err.println("3 - " + exceptionId + " - " + exception);
                                break;
                            case 4:
                                // data event
                                long dataId = fileByteBuffer.getLong();
                                long value = fileByteBuffer.getLong();
                                bytesConsumed += 2;
                                System.err.println("4 - " + dataId + " - " + value);
                                break;
                            case 5:
                                // type record
                                long typeId = fileByteBuffer.getLong();
                                String type = getNextString(fileByteBuffer);
                                bytesConsumed += 2 + type.length();
                                System.err.println("5 - " + typeId + " - " + type);
                                break;
                            case 6:
                                // weave info
                                String weaveInfo = getNextString(fileByteBuffer);
                                bytesConsumed += 1 + weaveInfo.length();
                                System.err.println("6 - " + weaveInfo);
                                break;
                            case 7:
                                // method info
                                System.err.println("7 - ");
                                break;
                            case 8:
                                // data info
                                System.err.println("8 - ");
                                break;
                            default:
                                System.err.println("Invalid event type found in file: " + eventType);
                                throw new Exception("invalid event type in file, cannot process further");
                        }
                        eventsRead++;
                        System.err.println("Consumed " + bytesConsumed + "/" + bytesRead + ": events = " + eventsRead);
                    }

                }
                boolean fileDeleted = file.delete();
                if (!fileDeleted) {
                    System.err.println("Failed to delete file " + file.getAbsolutePath());
                }

            } catch (Exception e) {
                err.log(e);
            }
        }
    }


    private String getNextString(ByteBuffer buffer) {
        int stringLength = buffer.getInt();
        byte[] str = new byte[stringLength];
        buffer.get(str);
        return new String(str);
    }

    public void writeClassInfo(String str) {
        try {
            out.writeInt(6);
            out.writeInt(str.length());
            out.writeBytes(str);
        } catch (IOException e) {
            err.log(e);
        }
    }

    public void writeMethodInfo(String str) {
        try {
            out.writeInt(7);
            out.writeInt(str.length());
            out.writeBytes(str);
        } catch (IOException e) {
            err.log(e);
        }
    }

    public void writeDataInfo(String str) {
        try {
            out.writeInt(8);
            out.writeInt(str.length());
            out.writeBytes(str);
        } catch (IOException e) {
            err.log(e);
        }
    }

    public void writeWeaveInfo(String toByteArray) {
        try {
            out.writeInt(6);
            out.writeInt(toByteArray.length());
            out.writeBytes(toByteArray);
        } catch (IOException e) {
            err.log(e);
        }
    }
}
