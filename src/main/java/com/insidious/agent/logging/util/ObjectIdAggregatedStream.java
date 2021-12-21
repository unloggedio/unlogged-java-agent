package com.insidious.agent.logging.util;

import java.io.IOException;


/**
 * This class added type ID management and file save features to ObjectIdMap class.
 */
public class ObjectIdAggregatedStream extends ObjectIdMap {

    private final String lineSeparator = "\n";
    private final BinaryRsocketAggregatedLogger aggregatedLogger;
    private final TypeIdAggregatedStreamMap typeToId;

    /**
     * Create an instance to record object types.
     *
     * @param typeToId         is an object to translate a type into an integer representing a type.
     * @param aggregatedLogger
     * @throws IOException
     */
    public ObjectIdAggregatedStream(BinaryRsocketAggregatedLogger aggregatedLogger, TypeIdAggregatedStreamMap typeToId) {
        super(16 * 1024 * 1024);
        this.typeToId = typeToId;
        this.aggregatedLogger = aggregatedLogger;
    }

    /**
     * Register a type for each new object.
     * This is separated from onNewObjectId because this method
     * calls TypeIdMap.createTypeRecord that may call a ClassLoader's method.
     * If the ClassLoader is also monitored by SELogger,
     * the call indirectly creates another object ID.
     */
    @Override
    protected void onNewObject(Object o) {
        typeToId.getTypeIdString(o.getClass());
    }

    /**
     * Record an object ID and its Type ID in a file.
     * In case of String and Throwable, this method also record their textual contents.
     */
    @Override
    protected void onNewObjectId(Object o, long id) {
        String typeId = typeToId.getTypeIdString(o.getClass());
        aggregatedLogger.writeNewObjectType(id, typeId);

        if (o instanceof String) {
            String stringObject = (String) o;
            aggregatedLogger.writeNewString(id, stringObject);
        } else if (o instanceof Throwable) {
            try {
                Throwable t = (Throwable) o;
                long causeId = getId(t.getCause());
                Throwable[] suppressed = t.getSuppressed();
                long[] suppressedId = new long[suppressed.length];
                for (int i = 0; i < suppressedId.length; ++i) {
                    suppressedId[i] = getId(suppressed[i]);
                }

                StringBuilder builder = new StringBuilder(1028);
                builder.append(id);
                builder.append(",M,");
                builder.append(t.getMessage());
                builder.append("\n");
                builder.append(id);
                builder.append(",CS,");
                builder.append(causeId);
                for (int i = 0; i < suppressedId.length; ++i) {
                    builder.append(",");
                    builder.append(suppressedId[i]);
                }
                builder.append("\n");

                StackTraceElement[] trace = t.getStackTrace();
                for (int i = 0; i < trace.length; ++i) {
                    builder.append(id);
                    builder.append(",S,");
                    StackTraceElement e = trace[i];
                    builder.append(e.isNativeMethod() ? "T," : "F, ");
                    builder.append(e.getClassName());
                    builder.append(",");
                    builder.append(e.getMethodName());
                    builder.append(",");
                    builder.append(e.getFileName());
                    builder.append(",");
                    builder.append(e.getLineNumber());
                    builder.append("\n");
                }
                aggregatedLogger.writeNewException(builder.toString());
            } catch (Throwable e) {
                // ignore all exceptions
            }
        }
    }

    /**
     * Close the files written by this object.
     */
    public synchronized void close() {

    }

}
