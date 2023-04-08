package com.videobug.agent.logging.io;

import com.esotericsoftware.kryo.Kryo;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.insidious.common.weaver.ClassInfo;
import com.insidious.common.weaver.DataInfo;
import com.insidious.common.weaver.EventType;
import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.util.AggregatedFileLogger;
import com.videobug.agent.logging.util.ObjectIdAggregatedStream;
import com.videobug.agent.logging.util.TypeIdAggregatedStreamMap;
import com.videobug.agent.weaver.WeaveLog;
import org.nustaq.serialization.*;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
    private final FSTConfiguration fstObjectMapper;
    private final AggregatedFileLogger aggregatedLogger;
    private final TypeIdAggregatedStreamMap typeToId;
    private final ObjectIdAggregatedStream objectIdMap;
    private final String includedPackage;
    private final Boolean DEBUG = Boolean.parseBoolean(System.getProperty("UNLOGGED_DEBUG"));
//    private final ThreadLocal<ByteArrayOutputStream> threadOutputBuffer =
//            ThreadLocal.withInitial(ByteArrayOutputStream::new);
    private final ThreadLocal<Boolean> isRecording = ThreadLocal.withInitial(() -> false);
    final private boolean serializeValues = true;
    private final Map<String, WeaveLog> classMap = new HashMap<>();
    private final Set<Integer> probesToRecord = new HashSet<>();
    private final Map<Integer, DataInfo> callProbes = new HashMap<>();
    private final SerializationMode SERIALIZATION_MODE = SerializationMode.JACKSON;
    private final ThreadLocal<ByteArrayOutputStream> output =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(1_000_000));
    //    private final Set<String> classesToIgnore = new HashSet<>();
    private final Kryo kryo;
    private final ObjectMapper objectMapper;

    private final Map<String, WeakReference<Object>> objectMap = new HashMap<>();

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

        this.includedPackage = includedPackage;
        this.aggregatedLogger = aggregatedLogger;
        typeToId = new TypeIdAggregatedStreamMap(this.aggregatedLogger, this);
        objectIdMap = new ObjectIdAggregatedStream(this.aggregatedLogger, typeToId, outputDir);

        if (SERIALIZATION_MODE == SerializationMode.KRYO) {
            kryo = new Kryo();
            kryo.register(byte[].class);
            kryo.register(LinkedHashMap.class);
            kryo.register(LinkedHashSet.class);
            objectMapper = null;
            fstObjectMapper = null;
        } else if (SERIALIZATION_MODE == SerializationMode.JACKSON) {
            // For 2.13.1
            JsonMappingException jme = new JsonMappingException(new DummyClosable(), "load class");
            jme.prependPath(new JsonMappingException.Reference("from dummy"));
            JsonMapper.Builder jacksonBuilder = JsonMapper.builder();
            jacksonBuilder.annotationIntrospector(new JacksonAnnotationIntrospector() {
                @Override
                public boolean hasIgnoreMarker(AnnotatedMember m) {
                    return false;
                }
            });
            DateFormat df = new SimpleDateFormat("MMM d, yyyy HH:mm:ss aaa");
            jacksonBuilder.defaultDateFormat(df);
            jacksonBuilder.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
            jacksonBuilder.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);
            jacksonBuilder.configure(SerializationFeature.WRITE_SELF_REFERENCES_AS_NULL, true);

            try {
                Class<?> hibernateClassPresent = Class.forName("org.hibernate.SessionFactory");
                Class<?> hibernateModule = Class.forName("com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module");
                Module module = (Module) hibernateModule.getDeclaredConstructor()
                        .newInstance();
                Class<?> featureClass = Class.forName(
                        "com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module$Feature");
                Method configureMethod = hibernateModule.getMethod("configure", featureClass, boolean.class);
                configureMethod.invoke(module, featureClass.getDeclaredField("FORCE_LAZY_LOADING")
                        .get(null), true);
                configureMethod.invoke(module, featureClass.getDeclaredField("REPLACE_PERSISTENT_COLLECTIONS")
                        .get(null), true);
                jacksonBuilder.addModule(module);
//                System.out.println("Loaded hibernate module");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
//                e.printStackTrace();
//                System.out.println("Failed to load hibernate module: " + e.getMessage());
                // hibernate module not found
                // add a warning in System.err here ?
            } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                     NoSuchFieldException e) {
                throw new RuntimeException(e);
            }

            // potentially
//            jacksonBuilder.findAndAddModules();
            List<String> jacksonModules = Arrays.asList(
                    "com.fasterxml.jackson.datatype.jdk8.Jdk8Module",
                    "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
                    "com.fasterxml.jackson.datatype.joda.JodaModule"
            );
            for (String jacksonModule : jacksonModules) {
                try {
                    //checks for presence of this module class, if not present throws exception
                    Class<?> jdk8Module = Class.forName(jacksonModule);
                    jacksonBuilder.addModule((Module) jdk8Module.getDeclaredConstructor()
                            .newInstance());
                } catch (ClassNotFoundException e) {
                    // jdk8 module not found
                } catch (InvocationTargetException
                         | InstantiationException
                         | IllegalAccessException
                         | NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }

            objectMapper = jacksonBuilder.build();
            objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            kryo = null;
            fstObjectMapper = null;
        } else if (SERIALIZATION_MODE == SerializationMode.FST) {

            FSTConfiguration defaultConfigMapper = FSTConfiguration.createDefaultConfiguration();
            defaultConfigMapper.setForceSerializable(true);
            defaultConfigMapper.registerSerializer(Timestamp.class, new FSTBasicObjectSerializer() {
                @Override
                public void writeObject(FSTObjectOutput out, Object toWrite, FSTClazzInfo clzInfo,
                                        FSTClazzInfo.FSTFieldInfo referencedBy, int streamPosition) throws IOException {
                    out.writeLong(((Timestamp) toWrite).getTime());
                }

                @Override
                public boolean alwaysCopy() {
                    return true;
                }

                @Override
                public Object instantiate(Class objectClass, FSTObjectInput in, FSTClazzInfo serializationInfo,
                                          FSTClazzInfo.FSTFieldInfo referencee, int streamPosition) throws Exception {
                    return new Timestamp(in.readLong());
                }
            }, true);

            fstObjectMapper = defaultConfigMapper;
            kryo = null;
            objectMapper = null;
        } else {
            fstObjectMapper = null;
            kryo = null;
            objectMapper = null;
        }


    }

    public ObjectIdAggregatedStream getObjectIdMap() {
        return objectIdMap;
    }

    /**
     * Close all file streams used by the object.
     */
    public void close() {
//        System.out.printf("[videobug] close event stream aggregated logger\n");
        objectIdMap.close();
        try {
            aggregatedLogger.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Object getObjectByClassName(String className) {
        if (!objectMap.containsKey(className)) {
            return null;
        }
        WeakReference<Object> objectWeakReference = objectMap.get(className);
        Object objectInstance = objectWeakReference.get();
        if (objectInstance == null) {
            objectMap.remove(className);
            return null;
        }
        return objectInstance;
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Object value) {
        if (isRecording.get()) {
            return;
        }
        String className;
        if (value != null) {
            className = value.getClass().getCanonicalName();
        } else {
            className = "";
        }
        if (className != null && !className.contains("Lambda")) {
            if (className.contains("_$")) {
                className = className.substring(0, className.indexOf("_$"));
            } else if (className.contains("$")) {
                className = className.substring(0, className.indexOf('$'));
            }
            objectMap.put(className, new WeakReference<>(value));
        }


        long objectId = objectIdMap.getId(value);

        if (serializeValues && probesToRecord.size() > 0 && probesToRecord.contains(dataId)) {

            if (DEBUG && value != null) {
                System.out.println("record serialized value for probe [" + dataId + "] -> " + value.getClass());
            }


            // write data into OutputStream
            byte[] bytes = new byte[0];
            try {
                isRecording.set(true);


//                if (className == null) {
//                    System.err.println("Class name is null: " + value + " - " + value.getClass());
//                }

//                if (value != null) {
//                    System.out.println("[" + dataId + "] Serialize class: " + value.getClass().getName());
//                }
                if (value instanceof Class) {
                    bytes = ((Class<?>) value).getCanonicalName().getBytes(StandardCharsets.UTF_8);
                } else if (className == null || className.startsWith("com.google")
                        || className.startsWith("org.apache.http")
                        || className.startsWith("java.util.stream")
                        || className.startsWith("org.elasticsearch.client")
                        || className.startsWith("org.hibernate")
                        || className.startsWith("ch.qos")
                        || className.startsWith("io.dropwizard")
                        || className.contains("java.lang.reflect")
                        || className.startsWith("org.redis")
                        || className.startsWith("redis")
                        || className.startsWith("co.elastic")
                        || className.startsWith("java.lang.Class")
                        || className.startsWith("io.undertow")
                        || className.startsWith("org.thymeleaf")
                        || className.startsWith("tech.jhipster")
                        || className.startsWith("com.github")
                        || className.startsWith("com.zaxxer")
                        || className.startsWith("org.glassfish")
                        || className.startsWith("com.fasterxml")
                        || className.startsWith("org.slf4j")
                        || className.startsWith("org.springframework")
                        || className.startsWith("java.io")
                        || className.contains("$Lambda$")
                        || className.contains("$$EnhancerBySpringCGLIB$$")
                        || className.startsWith("java.util.regex")
                        || className.startsWith("java.util.Base64")
                        || className.startsWith("java.util.concurrent")
                        || className.startsWith("com.amazon")
                        || className.startsWith("com.hubspot")
                        || className.endsWith("[]")
                        || value instanceof Iterator
                ) {
//                    System.err.println("Removing probe: " + dataId);
                    probesToRecord.remove(dataId);
                } else if (SERIALIZATION_MODE == SerializationMode.JACKSON) {
//                    System.err.println("To serialize class: " + className);
//                    objectMapper.writeValue(outputStream, value);
//                    outputStream.flush();
//                    bytes = outputStream.toByteArray();
                    bytes = objectMapper.writeValueAsBytes(value);
                    if (DEBUG) {
                        System.err.println(
                                "[" + dataId + "] record serialized value for probe [" + value.getClass() + "] [" + objectId + "] ->" +
                                        " " + new String(bytes));
                    }
                    // ######################################
                } else if (SERIALIZATION_MODE == SerializationMode.FST) {
//                    objectMapper.writeValue(outputStream, value);
//                    outputStream.flush();
//                    bytes = outputStream.toByteArray();
                    bytes = fstObjectMapper.asByteArray(value);
                    if (bytes.length > 10000) {
                        probesToRecord.remove(dataId);
                        bytes = new byte[0];
                    }
//                    System.err.println(
//                            "[" + dataId + "] record serialized value for probe [" + value.getClass() + "] [" + objectId + "] ->" +
//                                    " " + bytes.length + " : " + Base64.getEncoder().encodeToString(bytes));
                    // ######################################
                } else if (SERIALIZATION_MODE == SerializationMode.OOS) {
                    // ################# USING OOS #####################
                    // # using ObjectOutputStream
                    //                System.out.println("Registration " + registration.toString() + " - ");
//                    ObjectOutputStream oos = new ObjectOutputStream(out);
//                    serializer.serialize(out2, value);
//                    oos.writeObject(value);
                    // ######################################

                } else if (SERIALIZATION_MODE == SerializationMode.KRYO) {
                    // # using kryo
                    // ################ USING KRYO ######################
//                    Output output = outputContainer.get();
//                    ByteArrayOutputStream buffer = (ByteArrayOutputStream) output.getOutputStream();
//                    output.reset();
//                    kryo.writeObject(output, value);
//                    output.flush();
//                    bytes = output.toBytes();
                    // ######################################
                }


            } catch (Throwable e) {
                probesToRecord.remove(dataId);
//                if (value != null) {
//                    kryo.register(value.getClass());
//                    String message = e.getMessage();
//                System.err.println("ThrowSerialized [" + value.getClass().getCanonicalName() + "]" +
//                        " [" + dataId + "] error -> " + e.getMessage() + " -> " + e.getClass().getCanonicalName());
//                e.printStackTrace();
//                    if (message.startsWith("Class is not registered")) {
//                        String className = message.split(":")[1];
//                        try {
//                            Class<?> classType = Class.forName(className);
//                            kryo.register(classType);
//                        } catch (ClassNotFoundException ex) {
////                            ex.printStackTrace();
//                        }
//                    }
//                e.printStackTrace();
//                }
                // ignore if we cannot record the variable information
            } finally {
                isRecording.set(false);
            }
            aggregatedLogger.writeEvent(dataId, objectId, bytes);
//            outputStream.reset();
        } else {
//            System.err.println("No serialization for: " + dataId);
            aggregatedLogger.writeEvent(dataId, objectId);
        }

    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, int value) {
        if (isRecording.get()) {
            return;
        }

//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     */
    public void recordEvent(int dataId, long value) {
        if (isRecording.get()) {
            return;
        }

//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, byte value) {
        if (isRecording.get()) {
            return;
        }

//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, short value) {
        if (isRecording.get()) {
            return;
        }

//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, char value) {
        if (isRecording.get()) {
            return;
        }

//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, value);
        aggregatedLogger.writeEvent(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value (true = 1, false = 0).
     */
    public void recordEvent(int dataId, boolean value) {
        if (isRecording.get()) {
            return;
        }

        int longValue = value ? 1 : 0;
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, longValue);
        aggregatedLogger.writeEvent(dataId, longValue);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value preserving the information.
     */
    public void recordEvent(int dataId, double value) {
        if (isRecording.get()) {
            return;
        }

        long longValue = Double.doubleToRawLongBits(value);
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, longValue);
        aggregatedLogger.writeEvent(dataId, longValue);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value preserving the information.
     */
    public void recordEvent(int dataId, float value) {
        if (isRecording.get()) {
            return;
        }

        int longValue = Float.floatToRawIntBits(value);
//        System.out.printf("Record event in event stream aggregated logger %s -> %s\n", dataId, longValue);
        aggregatedLogger.writeEvent(dataId, longValue);
    }

    @Override
    public void recordWeaveInfo(byte[] byteArray, ClassInfo classIdEntry, WeaveLog log) {

        classMap.put(classIdEntry.getClassName(), log);
//        System.err.println("Record weave info for [" + classIdEntry.getClassName() + "]");
        if (!classIdEntry.getClassName()
                .contains("mongo") &&
                !classIdEntry.getClassName()
                        .contains("spring") &&
                !classIdEntry.getClassName()
                        .contains("redis")
        ) {
            List<Integer> newClassProbes = log.getDataEntries()
                    .stream()
                    .filter(e ->
                            e.getEventType() == EventType.CALL_PARAM ||
                                    e.getEventType() == EventType.METHOD_PARAM ||
                                    e.getEventType() == EventType.METHOD_NORMAL_EXIT ||
                                    e.getEventType() == EventType.CALL_RETURN)
                    .filter(e -> {
                        String type = e.getAttribute("Type", null);
                        if ("Ljava/util/Iterator;".equals(type)) {
                            return false;
                        }
                        return true;
                    })
                    .map(DataInfo::getDataId)
                    .collect(Collectors.toList());


            Map<Integer, DataInfo> callProbes1 = log.getDataEntries()
                    .stream()
                    .collect(Collectors.toMap(DataInfo::getDataId, e -> e));


            probesToRecord.addAll(newClassProbes);
            callProbes.putAll(callProbes1);
        }
        aggregatedLogger.writeWeaveInfo(byteArray);
    }

    @Override
    public void setRecording(boolean b) {
        isRecording.set(b);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public void registerClass(Integer id, Class<?> type) {
//        if (serializeValues) {
//            try {
//                Registration registration = kryo.register(type);
//            } catch (Throwable th) {
//                System.out.println("Failed to register kryo class: " + type.getCanonicalName() +
//                        " -> " + th.getMessage());
//            }
//        }
    }

    private class DummyClosable implements Closeable {

        @Override
        public void close() throws IOException {

        }
    }


}
