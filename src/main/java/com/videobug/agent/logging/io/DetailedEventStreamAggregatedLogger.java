package com.videobug.agent.logging.io;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.io.Output;
import com.google.gson.Gson;
import com.insidious.common.weaver.ClassInfo;
import com.insidious.common.weaver.DataInfo;
import com.insidious.common.weaver.EventType;
import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.util.AggregatedFileLogger;
import com.videobug.agent.logging.util.ObjectIdAggregatedStream;
import com.videobug.agent.logging.util.TypeIdAggregatedStreamMap;
import com.videobug.agent.weaver.WeaveLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;


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

    public static final Duration MILLI_1 = Duration.of(1, ChronoUnit.MILLIS);
    private final AggregatedFileLogger aggregatedLogger;
    private final TypeIdAggregatedStreamMap typeToId;
    private final ObjectIdAggregatedStream objectIdMap;
    private final String includedPackage;
    private final ThreadLocal<ByteArrayOutputStream> threadOutputBuffer =
            ThreadLocal.withInitial(ByteArrayOutputStream::new);
    final private boolean serializeValues = true;
    private final ThreadLocal<Output> outputContainer = ThreadLocal.withInitial(
            new Supplier<Output>() {
                @Override
                public Output get() {
                    return new Output(threadOutputBuffer.get());
                }
            }
    );
    private final Map<String, WeaveLog> classMap = new HashMap<>();
    private final List<Integer> probesToRecord = new ArrayList<>();
    private final SerializationMode SERIALIZATION_MODE = SerializationMode.GSON;
    Kryo kryo = new Kryo();
    Gson gson = new Gson();

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
        typeToId = new TypeIdAggregatedStreamMap(this.aggregatedLogger, this);
        objectIdMap = new ObjectIdAggregatedStream(this.aggregatedLogger, typeToId, outputDir);


        kryo.register(byte[].class);
        kryo.register(LinkedHashMap.class);
        kryo.register(LinkedHashSet.class);
    }

    public ObjectIdAggregatedStream getObjectIdMap() {
        return objectIdMap;
    }

    /**
     * Close all file streams used by the object.
     */
    public void close() {
        System.out.printf("[videobug] close event stream aggregated logger\n");
        objectIdMap.close();
        try {
            aggregatedLogger.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Object value) {
//        System.out.println("Record event [" + dataId + "] - [" + value + "]");
        long objectId = objectIdMap.getId(value);
        byte[] bytes = new byte[0];
        if (serializeValues && probesToRecord.size() > 0 && probesToRecord.contains(dataId)) {

            if (value != null) {
                System.out.println("record serialized value for probe [" + dataId + "] -> " + value.getClass());
                if (value instanceof Class) {
                    kryo.register((Class) value);
                }
            }


            // write data into OutputStream
            try {
//            String className = value.getClass().getCanonicalName().replaceAll("\\.", "/");

                // ############### USING GSON #######################

                if (SERIALIZATION_MODE == SerializationMode.GSON) {
                    // # using gson
                    String jsonValue = gson.toJson(value);
                    bytes = jsonValue.getBytes();
                    // ######################################
                }


                if (SERIALIZATION_MODE == SerializationMode.OOS) {
                    // ################# USING OOS #####################
                    // # using ObjectOutputStream
                    //                System.out.println("Registration " + registration.toString() + " - ");
//                    ObjectOutputStream oos = new ObjectOutputStream(out);
//                    serializer.serialize(out2, value);
//                    oos.writeObject(value);
                    // ######################################

                }


                // # using kryo
                // ################ USING KRYO ######################
                if (SERIALIZATION_MODE == SerializationMode.KRYO) {
                    Output output = outputContainer.get();
                    ByteArrayOutputStream buffer = (ByteArrayOutputStream) output.getOutputStream();
                    output.reset();
//                    if (value.getClass().getCanonicalName().contains("Mono")) {
//                        System.out.println("BGlocking for " + value);
//
//                        try {
//                            Object block = ((Mono) value).block(MILLI_1);
//                            System.out.println("BGlocking unlocked for " + block);
//                            kryo.writeObject(output, block);
//                        } catch (Exception e) {
//                            System.out.println("BGlocking unlocked failed " + e.getMessage());
//                        }
//
//                    } else {
                    kryo.writeObject(output, value);
//                    }
                    output.flush();
                    bytes = buffer.toByteArray();
                    // ######################################
                }


            } catch (Throwable e) {
//                if (value != null) {
//                    kryo.register(value.getClass());
//                    String message = e.getMessage();
//                    System.err.println("ThrowSerialized [" + value.getClass().getCanonicalName() + "]" +
//                            " [" + dataId + "] error -> " + message);
//                    if (message.startsWith("Class is not registered")) {
//                        String className = message.split(":")[1];
//                        try {
//                            Class<?> classType = Class.forName(className);
//                            kryo.register(classType);
//                        } catch (ClassNotFoundException ex) {
////                            ex.printStackTrace();
//                        }
//                    }
////                    e.printStackTrace();
//                }
                // ignore if we cannot record the variable information
            }
            aggregatedLogger.writeEvent(dataId, objectId, bytes);
        } else {
            aggregatedLogger.writeEvent(dataId, objectId);
        }

    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Integer value) {
//        if (value == null) {
//            aggregatedLogger.writeEvent(dataId, 0);
//        } else {
//            aggregatedLogger.writeEvent(dataId, value.longValue());
//        }
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Long value) {
//        if (value == null) {
//            aggregatedLogger.writeEvent(dataId, 0);
//        } else {
//            aggregatedLogger.writeEvent(dataId, value.longValue());
//        }
//
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Short value) {
//        if (value == null) {
//            aggregatedLogger.writeEvent(dataId, 0);
//        } else {
//            aggregatedLogger.writeEvent(dataId, value.longValue());
//        }
//
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Boolean value) {
//        aggregatedLogger.writeEvent(dataId, value == null ? 0 : value ? 1 : 0);
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Float value) {
//        if (value == null) {
//            aggregatedLogger.writeEvent(dataId, 0);
//        } else {
//            aggregatedLogger.writeEvent(dataId, value.longValue());
//        }
//
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Byte value) {
//        aggregatedLogger.writeEvent(dataId, value);
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Date value) {
//        if (value == null) {
//            aggregatedLogger.writeEvent(dataId, 0);
//        } else {
//            aggregatedLogger.writeEvent(dataId, value.getTime());
//        }
//
//    }
//
//    /**
//     * Record an event and an object.
//     * The object is translated into an object ID.
//     */
//    public void recordEvent(int dataId, Double value) {
//        if (value != null) {
//            long longValue = Double.doubleToRawLongBits(value);
//            aggregatedLogger.writeEvent(dataId, longValue);
//        } else {
//            aggregatedLogger.writeEvent(dataId, 0);
//        }
//    }

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
    public void recordWeaveInfo(byte[] byteArray, ClassInfo classIdEntry, WeaveLog log) {

        classMap.put(classIdEntry.getClassName(), log);
        System.err.println("Record weave info for [" + classIdEntry.getClassName() + "]");
        if (!classIdEntry.getClassName().contains("mongo") &&
                !classIdEntry.getClassName().contains("spring") &&
                !classIdEntry.getClassName().contains("redis")
        ) {
            List<Integer> newClassProbes = log.getDataEntries()
                    .stream()
                    .filter(e ->
                            e.getEventType() == EventType.CALL_PARAM ||
                                    e.getEventType() == EventType.METHOD_PARAM ||
                                    e.getEventType() == EventType.METHOD_NORMAL_EXIT ||
                                    e.getEventType() == EventType.CALL_RETURN
                    )
                    .map(DataInfo::getDataId)
                    .collect(Collectors.toList());
            probesToRecord.addAll(newClassProbes);
            System.err.println("Record serialized value for probes: " + newClassProbes);
        }
        aggregatedLogger.writeWeaveInfo(byteArray);
    }

    @Override
    public void registerClass(Integer id, Class<?> type) {
        if (serializeValues) {
            try {
                Registration registration = kryo.register(type);
            } catch (Throwable th) {
                System.out.println("Failed to register kryo class: " + type.getCanonicalName() +
                        " -> " + th.getMessage());
            }
        }
    }


}
