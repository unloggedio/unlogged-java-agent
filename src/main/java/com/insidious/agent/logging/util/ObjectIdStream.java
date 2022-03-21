package com.insidious.agent.logging.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.RSocket;
import io.rsocket.metadata.CompositeMetadataCodec;
import io.rsocket.metadata.RoutingMetadata;
import io.rsocket.metadata.TaggingMetadataCodec;
import io.rsocket.metadata.WellKnownMimeType;
import io.rsocket.util.DefaultPayload;

import java.io.File;
import java.io.IOException;
import java.util.Collections;


/**
 * This class added type ID management and file save features to ObjectIdMap class.
 */
public class ObjectIdStream extends ObjectIdMap {

    private final String lineSeparator = "\n";
    private final RSocket rSocket;

    private StringFileListStream objectIdList;
    private TypeIdMap typeToId;
    private FileNameGenerator filenames;
    private StringFileListStream exceptionList;

    private StringContentFile stringContentList;

    public static final long ID_NOT_FOUND = -1;

    public static long cacheHit = 0;
    public static long cacheMiss = 0;

    /**
     * Create an instance to record object types.
     *
     * @param outputDir    is a directory for output files.
     * @param recordString is a flag to recording string contents.
     *                     If the flag is true, this object records the contents of String objects in files.
     * @param typeToId     is an object to translate a type into an integer representing a type.
     * @param rSocket
     * @param processId
     * @throws IOException
     */
    public ObjectIdStream(File outputDir, boolean recordString, TypeIdMap typeToId, RSocket rSocket, Integer processId) throws IOException {
        super(64 * 1024 * 1024);
        this.typeToId = typeToId;
        this.rSocket = rSocket;

        filenames = new FileNameGenerator(outputDir, "LOG$ObjectTypes", ".txt");
        objectIdList = new StringFileListStream(filenames, 10000000, false);

        exceptionList = new StringFileListStream(new FileNameGenerator(outputDir, "LOG$Exceptions", ".txt"), 1000000, false);

        if (recordString) {
            stringContentList = new StringContentFile(outputDir);
        }
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
        String element = Long.toString(id) + "," + typeId + lineSeparator;
        objectIdList.write(element);

        if (o instanceof String) {
            if (stringContentList != null) {
                String stringObject = (String) o;
                stringContentList.write(id, stringObject);
                ByteBuf data = ByteBufAllocator.DEFAULT.buffer();
                data.writeLong(id);
                data.writeInt(stringObject.length());
                data.writeBytes(stringObject.getBytes());

                CompositeByteBuf messageMetadata = ByteBufAllocator.DEFAULT.compositeBuffer();
                RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
                        ByteBufAllocator.DEFAULT, Collections.singletonList("string-mapping")
                );


                CompositeMetadataCodec.encodeAndAddMetadata(messageMetadata,
                        ByteBufAllocator.DEFAULT,
                        WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                        routingMetadata.getContent()
                );

                rSocket.fireAndForget(DefaultPayload.create(data, messageMetadata)).subscribe();
            }
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
                builder.append(Long.toString(id));
                builder.append(",M,");
                builder.append(t.getMessage());
                builder.append("\n");
                builder.append(Long.toString(id));
                builder.append(",CS,");
                builder.append(Long.toString(causeId));
                for (int i = 0; i < suppressedId.length; ++i) {
                    builder.append(",");
                    builder.append(Long.toString(suppressedId[i]));
                }
                builder.append("\n");

                StackTraceElement[] trace = t.getStackTrace();
                for (int i = 0; i < trace.length; ++i) {
                    builder.append(Long.toString(id));
                    builder.append(",S,");
                    StackTraceElement e = trace[i];
                    builder.append(e.isNativeMethod() ? "T," : "F, ");
                    builder.append(e.getClassName());
                    builder.append(",");
                    builder.append(e.getMethodName());
                    builder.append(",");
                    builder.append(e.getFileName());
                    builder.append(",");
                    builder.append(Integer.toString(e.getLineNumber()));
                    builder.append("\n");
                }
                exceptionList.write(builder.toString());

            } catch (Throwable e) {
                // ignore all exceptions
            }
        }
    }

    /**
     * Close the files written by this object.
     */
    public synchronized void close() {
        objectIdList.close();
        exceptionList.close();
        if (stringContentList != null) stringContentList.close();
    }

}
