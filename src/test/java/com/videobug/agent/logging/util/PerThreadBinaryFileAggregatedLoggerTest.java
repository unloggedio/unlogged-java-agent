package com.videobug.agent.logging.util;

import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.perthread.*;
import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import orestes.bloomfilter.FilterBuilder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class PerThreadBinaryFileAggregatedLoggerTest {


    private final Logger logger = LoggerFactory.getLogger(PerThreadBinaryFileAggregatedLoggerTest.class);

    @Test
    public void testLoggerIndex1() throws InterruptedException, IOException {
        IErrorLogger errorLogger = new IErrorLogger() {
            @Override
            public void log(Throwable t) {
                logger.error("", t);

            }

            @Override
            public void log(String msg) {
                logger.info(msg);
            }

            @Override
            public void close() {

            }
        };
        PerThreadBinaryFileAggregatedLogger eventLogger = new PerThreadBinaryFileAggregatedLogger(
                "test-output-" + new Date().getTime(), errorLogger,
                "token", "sessionId", "serverAddress", 64);

        long start = System.currentTimeMillis();
        int eventCount = 2000000;
        for (int i = 0; i < eventCount; i++) {

            eventLogger.writeEvent(i, i * 2);
            eventLogger.writeNewTypeRecord(i * 3, "hello-type-" + i * 3, "therecord");
            eventLogger.writeNewString(i * 4, "hello-string-" + i * 4);
            eventLogger.writeNewObjectType(i * 5, i * 3);
//            if (i % (eventCount / 20) == 0) {
//                Thread.sleep(3000);
//            }
        }
        long end = System.currentTimeMillis();
        logger.info("wrote [{}] events in [{}] ms, [{}] write/ms", eventCount, end - start, eventCount / (end - start));

//        Thread.sleep(20000);
        eventLogger.shutdown();
        Thread.sleep(1000);

    }

    @Test
    public void chronicleQueueTest() throws IOException {
        try (ChronicleQueue queue = SingleChronicleQueueBuilder.single("queue-dir").build()) {
            // Obtain an ExcerptAppender
            ExcerptAppender appender = queue.acquireAppender();

            ClassAliasPool.CLASS_ALIASES.addAlias(UploadFile.class);
            UploadFileQueue uploadFileQueue = queue.acquireAppender().methodWriter(UploadFileQueue.class);
            ExcerptTailer tailer = queue.createTailer();

            IErrorLogger logger = new IErrorLogger() {
                @Override
                public void log(Throwable t) {
                    PerThreadBinaryFileAggregatedLoggerTest.this.logger.info("throwable ", t);
                }

                @Override
                public void log(String msg) {
                    PerThreadBinaryFileAggregatedLoggerTest.this.logger.info("message: {}", msg);
                }

                @Override
                public void close() {

                }
            };
            ArchivedIndexWriter archivedIndexWriter = new ArchivedIndexWriter(new File("Archive.zip"), logger);
            FileNameGenerator indexFileNameGenerator = new FileNameGenerator(new File("test"), "index", ".zip");
            BlockingQueue<UploadFile> fileList = new ArrayBlockingQueue<>(1024);
            RawFileCollector rawFileCollector = new RawFileCollector(50, indexFileNameGenerator, fileList, logger);
            UploadFileQueueImpl fileQueue = new UploadFileQueueImpl(rawFileCollector);
            MethodReader reader = queue.createTailer().methodReader(fileQueue);

            uploadFileQueue.add(new UploadFile("1", 1,
                            new FilterBuilder(10, 0.01).buildBloomFilter(),
                            new FilterBuilder(10, 0.01).buildBloomFilter()
                    )
            );
            reader.readOne();

//            appender.writeDocument(w -> w.writeBytes(e -> {
//                e.write(new byte[]{1,2,3,4});
//            }));

            // Writes: TestMessage
//            appender.writeText("TestMessage");


//            ByteBuffer baos = ByteBuffer.allocate(1024);
//            byte[] bytes = new byte[64];
//            tailer.readBytes(b -> {
//                try {
//                    b.copyTo(bytes);
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            });
//            assertEquals(4, bytes[0]);

//            byte[] bytes1 = new byte[64];
//            tailer.readBytes(b -> {
//                try {
//                    b.copyTo(bytes1);
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            });
//            assertEquals(5, bytes1[0]);

//            assertEquals("TestMessage", tailer.readText());
        }
    }

}