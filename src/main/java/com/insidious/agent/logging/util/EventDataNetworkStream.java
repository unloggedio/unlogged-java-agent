package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.RSocket;
import io.rsocket.metadata.*;
import io.rsocket.util.DefaultPayload;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
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
public class EventDataNetworkStream {

    /**
     * The number of events stored in a single file.
     */
    public static final int MAX_EVENTS_PER_FILE = 10000000;

    /**
     * The data size of an event.
     */
    public static final int BYTES_PER_EVENT = 16;
    private final RSocket rSocket;
    private final Integer processId;

    private FileNameGenerator files;
    private DataOutputStream out;
    private IErrorLogger err;
    private int count;

    /**
     * This object records the number of threads observed by SELogger.
     */
    private static final AtomicInteger nextThreadId = new AtomicInteger(0);

    /**
     * Assign an integer to this thread.
     */
    private static ThreadLocal<Integer> threadId = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return nextThreadId.getAndIncrement();
        }
    };

    /**
     * Create an instance of stream.
     *
     * @param rSocket   is an Rsocket connection to the server.
     * @param logger    is to report errors that occur in this class.
     * @param processId
     */
    public EventDataNetworkStream(RSocket rSocket, IErrorLogger logger, Integer processId) {
        this.rSocket = rSocket;
        this.processId = processId;
        err = logger;
        count = 0;


    }

    /**
     * Write an event data into a file.  The thread ID is also recorded.
     *
     * @param dataId specifies an event and its bytecode location.
     * @param value  specifies a data value observed in the event.
     */
    public synchronized void write(int dataId, long value) {

        CompositeByteBuf metadata = ByteBufAllocator.DEFAULT.compositeBuffer();
        RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
                ByteBufAllocator.DEFAULT, Collections.singletonList("data-event")
        );
        CompositeMetadataCodec.encodeAndAddMetadata(metadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                routingMetadata.getContent()
        );

        ByteBuf out = ByteBufAllocator.DEFAULT.buffer();

        out.writeInt(processId);
        out.writeInt(threadId.get());
        out.writeLong(System.nanoTime());
        out.writeInt(dataId);
        out.writeLong(value);
        count++;
        rSocket.fireAndForget(DefaultPayload.create(out, metadata)).subscribe();
    }

    /**
     * Close the stream.
     */
    public synchronized void close() {
        rSocket.dispose();
    }

}
