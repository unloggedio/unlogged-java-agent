package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;

import java.io.*;
import java.util.ArrayList;
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
    private FileNameGenerator files;
    private DataOutputStream out;
    private IErrorLogger err;
    private int count;

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
            File nextFile = target.getNextFile();
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(nextFile), 32 * 1024));
            System.out.printf("Create aggregated logger -> %s\n", nextFile.getAbsolutePath());
            count = 0;
        } catch (IOException e) {
            err.log(e);
        }
    }

    /**
     * Write an event data into a file.  The thread ID is also recorded.
     *
     * @param dataId specifies an event and its bytecode location.
     * @param value  specifies a data value observed in the event.
     */
    public synchronized void write(int dataId, long value) {
        try {
            if (count >= MAX_EVENTS_PER_FILE) {
                out.close();
                out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                count = 0;
            }
            out.writeInt(0);
            out.writeInt(dataId);
            out.writeInt(threadId.get());
            out.writeLong(value);
            count++;
        } catch (IOException e) {
            out = null;
            err.log(e);
        }
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
                    out.close();
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                    count = 0;
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
                    out.close();
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                    count = 0;
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
                    out.close();
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                    count = 0;
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
                    out.close();
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                    count = 0;
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
                    out.close();
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                    count = 0;
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
                out.close();
                out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(files.getNextFile()), 32 * 1024));
                count = 0;
            }
            out.writeBytes(string);
            count++;
        } catch (IOException e) {
            err.log(e);
        }
    }

    @Override
    public void run() {

    }
}
