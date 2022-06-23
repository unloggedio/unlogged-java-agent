package com.videobug.agent.logging;

import com.videobug.agent.logging.io.*;
import com.videobug.agent.logging.io.LatestEventLogger.ObjectRecordingStrategy;
import com.videobug.agent.logging.util.AggregatedFileLogger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.LinkedList;


/**
 * This class provides static members for logging execution.
 * The weaver component inserts method calls to this class.
 * Array-related recording methods (recordArrayLoad, recordArrayStore,
 * recordMultiNewArray, and recordMultiNewArrayContents) are provided
 * to simplify the weaver's code.
 */
public class Logging {

    /**
     * The instance to record events.
     * A system must call one of initialize methods OR directly set an instance to this field.
     */
    static IEventLogger INSTANCE;

    /**
     * Create a stream logger and stores it to the INSTANCE field.
     * The stream logger stores a sequence of events into files.
     *
     * @param outputDir    specifies a directory where files are created.
     * @param recordString If this flag is true, the logger records string objects into files.
     * @param errorLogger  specifies a logger to record error messages reported by the logger.
     * @return the created logger instance.
     */
    public static IEventLogger initializeStreamLogger(File outputDir, boolean recordString, IErrorLogger errorLogger) {
        try {
            INSTANCE = new EventStreamLogger(errorLogger, outputDir, recordString);
            return INSTANCE;
        } catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static Integer getProcessId(final Integer fallback) {
        // Note: may fail in some JVM implementations
        // therefore fallback has to be provided

        // something like '<pid>@<hostname>', at least in SUN / Oracle JVMs
        final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
        final int index = jvmName.indexOf('@');

        if (index < 1) {
            // part before '@' empty (index = 0) / '@' not found (index = -1)
            return fallback;
        }

        try {
            return Integer.parseInt(jvmName.substring(0, index));
        } catch (NumberFormatException e) {
            // ignore
        }
        return fallback;
    }

    /**
     * Create a frequency logger and stores it to the INSTANCE field.
     * The logger records only the frequency of events.
     *
     * @param outputDir specifies a directory where files are created.
     * @return the created logger instance.
     */
    public static IEventLogger initializeFrequencyLogger(File outputDir) {
        INSTANCE = new EventFrequencyLogger(outputDir);
        return INSTANCE;
    }

    /**
     * Create a data logger and stores it to the INSTANCE field.
     * The logger records the latest k events for each event type (dataId) with thread ID asd timestamps.
     * Although it may miss some frequent events, it works with a limited size of storage.
     *
     * @param outputDir  specifies a directory where files are created.
     * @param bufferSize specifies the buffer size k.
     * @param outputJson generates a data file in a JSON format
     * @return the created logger instance.
     */
    public static IEventLogger initializeLatestEventTimeLogger(File outputDir, int bufferSize, ObjectRecordingStrategy keepObject, boolean outputJson) {
        INSTANCE = new LatestEventLogger(outputDir, bufferSize, keepObject, outputJson);
        return INSTANCE;
    }

    /**
     * Create a logger and stores it to the INSTANCE field.
     * The logger simply discards events.
     *
     * @return the created logger instance.
     */
    public static IEventLogger initializeDiscardLogger() {
        INSTANCE = new DiscardLogger();
        return INSTANCE;
    }


    public static EventStreamAggregatedLogger initialiseAggregatedLogger(
            IErrorLogger errorLogger,
            AggregatedFileLogger aggregatedLogger,
            File outputDir) throws IOException {
        EventStreamAggregatedLogger instance = new EventStreamAggregatedLogger(outputDir, aggregatedLogger);
        INSTANCE = instance;
        return instance;
    }

    /**
     * Create a logger and stores it to the INSTANCE field.
     * The logger keeps events on memory.
     *
     * @return the created logger instance.
     */
    public static MemoryLogger initializeForTest() {
        MemoryLogger m = new MemoryLogger();
        INSTANCE = m;
        return m;
    }

    /**
     * A method to record an event associated to an object.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(Object value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a throwable object.
     * This method is defined for type checking.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(Throwable value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a boolean value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(boolean value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a byte value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(byte value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a char value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(char value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a short integer value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(short value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to an integer value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(int value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a long integer value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(long value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a floating point number.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(float value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event associated to a double value.
     *
     * @param value
     * @param dataId
     */
    public static void recordEvent(double value, int dataId) {
        INSTANCE.recordEvent(dataId, value);
    }

    /**
     * A method to record an event without a data value.
     *
     * @param dataId dataId of the probe which generated the event
     */
    public static void recordEvent(int dataId) {
        INSTANCE.recordEvent(dataId, 0);
    }

    /**
     * A method to record an ArrayLoad event.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param dataId specifies an event.
     */
    public static void recordArrayLoad(Object array, int index, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, byte value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, char value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, double value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, float value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, int value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, long value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, short value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record an ArrayStore event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param index  specifies an array index.
     * @param value  specifies a data written to the array.
     * @param dataId specifies an event.
     */
    public static void recordArrayStore(Object array, int index, Object value, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        INSTANCE.recordEvent(dataId + 1, index);
        INSTANCE.recordEvent(dataId + 2, value);
    }

    /**
     * A method to record a MultiNewArray event.
     * This method is prepared to reduce the number of instructions for logging.
     *
     * @param array  specifies an array object.
     * @param dataId specifies an event.
     */
    public static void recordMultiNewArray(Object array, int dataId) {
        INSTANCE.recordEvent(dataId, array);
        recordMultiNewArrayContents((Object[]) array, dataId);
    }

    /**
     * This method scans the contents of an array and records their IDs.
     */
    private static void recordMultiNewArrayContents(Object[] array, int dataId) {
        LinkedList<Object[]> arrays = new LinkedList<Object[]>();
        arrays.addFirst(array);
        while (!arrays.isEmpty()) {
            Object[] asArray = arrays.removeFirst();
            INSTANCE.recordEvent(dataId + 1, asArray);
            for (int index = 0; index < asArray.length; ++index) {
                Object element = asArray[index];
                Class<?> elementType = element.getClass();
                if (element != null && elementType.isArray()) {
                    INSTANCE.recordEvent(dataId + 2, element);
                    if (elementType.getComponentType().isArray()) {
                        arrays.addLast((Object[]) element);
                    }
                }
            }
        }
    }


    public static void recordWeaveInfo(byte[] byteArray) {
        INSTANCE.recordWeaveInfo(byteArray);
    }
}
