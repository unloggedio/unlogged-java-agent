package com.videobug.agent.logging.util;

import com.videobug.agent.logging.IErrorLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.RSocket;
import io.rsocket.metadata.CompositeMetadataCodec;
import io.rsocket.metadata.RoutingMetadata;
import io.rsocket.metadata.TaggingMetadataCodec;
import io.rsocket.metadata.WellKnownMimeType;
import io.rsocket.util.DefaultPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a stream specialized to write a sequence of events into files.
 * A triple of data ID, thread ID, and a value observed in the event is recorded.
 * <p>
 * While a regular stream like FileOutputStream generates a single file,
 * this stream creates a number of files whose size is limited by the number of events
 * (MAX_EVENTS_PER_FILE field).
 */
public class EventDataNetworkStream implements Runnable {

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
    private final RSocket rSocket;
    private final Integer processId;
    private final CompositeByteBuf metadata;
    private final IErrorLogger err;
    private final int count;
    private final BlockingQueue<ByteBuf> bufferList = new LinkedBlockingQueue<>(1024 * 1024 * 1024);
    private FileNameGenerator files;

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
        metadata = ByteBufAllocator.DEFAULT.compositeBuffer();

        RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
                ByteBufAllocator.DEFAULT, Collections.singletonList("data-event")
        );
        CompositeMetadataCodec.encodeAndAddMetadata(metadata,
                ByteBufAllocator.DEFAULT,
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
                routingMetadata.getContent()
        );
        new Thread(this).start();
//        new Thread(this).start();
//        new Thread(this).start();
//        new Thread(this).start();
//        new Thread(this).start();
//        new Thread(this).start();
//        new Thread(this).start();
//        new Thread(this).start();


    }

    /**
     * Write an event data into a file.  The thread ID is also recorded.
     *
     * @param dataId specifies an event and its bytecode location.
     * @param value  specifies a data value observed in the event.
     */
    public void write(int dataId, long value) {

        ByteBuf out = ByteBufAllocator.DEFAULT.buffer();

        out.writeInt(processId);
        out.writeInt(threadId.get());
        out.writeLong(System.nanoTime());
        out.writeInt(dataId);
        out.writeLong(value);
        bufferList.add(out);
    }

    /**
     * Close the stream.
     */
    public synchronized void close() {
        rSocket.dispose();
    }

    @Override
    public void run() {
        System.out.println("Consumer started");
        ArrayList<ByteBuf> buffers = new ArrayList<>(10000);
        while (true) {
            while (bufferList.isEmpty()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            synchronized (bufferList) {
                int drainCount = bufferList.drainTo(buffers);
                System.err.println("Size is: " + bufferList.size() + " Drain Count: " + drainCount);
            }
            buffers.parallelStream().forEach(b -> {
                if (b == null) {
                    return;
                }
                rSocket.fireAndForget(DefaultPayload.create(b, metadata.copy())).subscribe();
            });
            buffers.clear();
        }
    }
}
