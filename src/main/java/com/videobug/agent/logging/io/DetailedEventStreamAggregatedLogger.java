package com.videobug.agent.logging.io;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.io.Output;
import com.google.gson.Gson;
import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.util.AggregatedFileLogger;
import com.videobug.agent.logging.util.ObjectIdAggregatedStream;
import com.videobug.agent.logging.util.TypeIdAggregatedStreamMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Date;

import static java.lang.System.out;

/**
 * This class is an implementation of IEventLogger that records
 * a sequence of runtime events in files.
 * <p>
 * The detailed recorder serializes all the object values in addition to the object id being
 * otherwise recorded. The serialized data is to be used for test case generation
 * <p>
 * This object creates three types of files:
 * 1. log-*.slg files recording a sequence of events,
 * 2. LOG$Types.txt recording a list of type IDs and their corresponding type names,
 * 3. ObjectIdMap recording a list of object IDs and their type IDs.
 * Using the second and third files, a user can know classes in an execution trace.
 */
public class DetailedEventStreamAggregatedLogger implements IEventLogger {

    private final AggregatedFileLogger aggregatedLogger;
    private final TypeIdAggregatedStreamMap typeToId;
    private final ObjectIdAggregatedStream objectIdMap;
    private final String includedPackage;

    /**
     * Create an instance of logging object.
     *
     * @param includedPackage
     * @param outputDir        specifies an object to record errors that occur in this class
     * @param aggregatedLogger writer
     */
    public DetailedEventStreamAggregatedLogger(
            String includedPackage, File outputDir,
            AggregatedFileLogger aggregatedLogger
    ) throws IOException {
//        System.out.printf("[videobug] new event stream aggregated logger\n");
        this.includedPackage = includedPackage;
        this.aggregatedLogger = aggregatedLogger;
        typeToId = new TypeIdAggregatedStreamMap(this.aggregatedLogger);
        objectIdMap = new ObjectIdAggregatedStream(this.aggregatedLogger, typeToId, outputDir);
    }

    public ObjectIdAggregatedStream getObjectIdMap() {
        return objectIdMap;
    }

    /**
     * Close all file streams used by the object.
     */
    public void close() {
        out.printf("[videobug] close event stream aggregated logger\n");
        objectIdMap.close();
        try {
            aggregatedLogger.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    Kryo kryo = new Kryo();
    Gson gson = new Gson();

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Object value) {
        long objectId = objectIdMap.getId(value);
//        ElsaSerializer serializer = new ElsaMaker().make();
        // Elsa Serializer takes DataOutput and DataInput.
        // Use streams to create it.
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] bytes = new byte[0];
        // write data into OutputStream
        try {
            String className = value.getClass().getCanonicalName().replaceAll("\\.", "/");
            if (className.startsWith(includedPackage)) {

//                String jsonValue = gson.toJson(value);
                kryo.register(value.getClass());
//                System.out.println("Registration " + registration.toString() + " - ");
//                ObjectOutputStream oos = new ObjectOutputStream(out);
//                            serializer.serialize(out2, value);
//                oos.writeObject(value);
                Output output = new Output(out);
                kryo.writeObject(output, value);
                output.close();
                bytes = out.toByteArray();
//                if (bytes == null) {
//                    bytes = new byte[0];
//                }
//                bytes = jsonValue.getBytes();
//                System.out.println("Serialized [" + value.getClass().getCanonicalName() + "] [" + dataId + "]" + bytes.length + "  -> [" + new String(bytes) + "]");
//                gson.fromJson(jsonValue, Gson.class);
            }
        } catch (Throwable e) {
            if (value != null) {
//                System.err.println("ThrowSerialized [" + value.getClass().getCanonicalName() + "]" +
//                        " [" + dataId + "] error -> " + e.getMessage());
//                e.printStackTrace();
            }
            // ignore if we cannot record the variable information
        }

        aggregatedLogger.writeEvent(dataId, objectId, bytes);
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Integer value) {
        if (value == null) {
            aggregatedLogger.writeEvent(dataId, 0);
        } else {
            aggregatedLogger.writeEvent(dataId, value.longValue());
        }
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Long value) {
        if (value == null) {
            aggregatedLogger.writeEvent(dataId, 0);
        } else {
            aggregatedLogger.writeEvent(dataId, value.longValue());
        }

    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Short value) {
        if (value == null) {
            aggregatedLogger.writeEvent(dataId, 0);
        } else {
            aggregatedLogger.writeEvent(dataId, value.longValue());
        }

    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Boolean value) {
        aggregatedLogger.writeEvent(dataId, value == null ? 0 : value ? 1 : 0);
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Float value) {
        if (value == null) {
            aggregatedLogger.writeEvent(dataId, 0);
        } else {
            aggregatedLogger.writeEvent(dataId, value.longValue());
        }

    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Byte value) {
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Date value) {
        if (value == null) {
            aggregatedLogger.writeEvent(dataId, 0);
        } else {
            aggregatedLogger.writeEvent(dataId, value.getTime());
        }

    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Double value) {
        if (value != null) {
            long longValue = Double.doubleToRawLongBits(value);
            aggregatedLogger.writeEvent(dataId, longValue);
        } else {
            aggregatedLogger.writeEvent(dataId, 0);
        }
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, int value) {
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     */
    public void recordEvent(int dataId, long value) {
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, byte value) {
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, short value) {
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, char value) {
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value (true = 1, false = 0).
     */
    public void recordEvent(int dataId, boolean value) {
        int longValue = value ? 1 : 0;
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, longValue);
        aggregatedLogger.writeEvent(dataId, longValue);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value preserving the information.
     */
    public void recordEvent(int dataId, double value) {
        long longValue = Double.doubleToRawLongBits(value);
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, longValue);
        aggregatedLogger.writeEvent(dataId, longValue);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value preserving the information.
     */
    public void recordEvent(int dataId, float value) {
        int longValue = Float.floatToRawIntBits(value);
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, longValue);
        aggregatedLogger.writeEvent(dataId, longValue);
    }

    @Override
    public void recordWeaveInfo(byte[] byteArray) {
        aggregatedLogger.writeWeaveInfo(byteArray);
    }


}
