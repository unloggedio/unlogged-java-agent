package com.videobug.agent.logging.util;

import com.insidious.common.parser.KaitaiInsidiousClassWeaveParser;
import com.insidious.common.weaver.ClassInfo;
import com.insidious.common.weaver.Descriptor;
import com.insidious.common.weaver.EventType;
import com.insidious.common.weaver.LogLevel;
import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import com.videobug.agent.logging.perthread.RawFileCollector;
import com.videobug.agent.weaver.WeaveConfig;
import com.videobug.agent.weaver.WeaveLog;
import com.videobug.agent.weaver.Weaver;
import io.kaitai.struct.ByteBufferKaitaiStream;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        NetworkClient networkClient = new NetworkClient("serverAddress",
                "sessionId", "token", errorLogger);

        String outputDirName = "test-output-" + new Date().getTime();

        File outputDir = new File(outputDirName);
        outputDir.mkdirs();
        RawFileCollector fileCollector = new RawFileCollector(64,
                new FileNameGenerator(outputDir, "index-", ".zip"), networkClient, errorLogger);

        FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");

        PerThreadBinaryFileAggregatedLogger eventLogger = new PerThreadBinaryFileAggregatedLogger(
                fileNameGenerator1, errorLogger, fileCollector);

        long start = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(12);
        int eventCount = 2000;
        for (int i = 0; i < eventCount; i++) {
            executorService.submit(new DummyRunnable(i, eventLogger));
        }
        long end = System.currentTimeMillis();
        logger.info("wrote [{}] events in [{}] ms, [{}] write/ms", eventCount, end - start, eventCount / (end - start));

        Thread.sleep(500);
        eventLogger.shutdown();
        Thread.sleep(500);

    }

    @Test
    public void testLoggerIndex2() throws InterruptedException, IOException {
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

        NetworkClient networkClient = new NetworkClient("serverAddress",
                "sessionId", "token", errorLogger);

        String outputDirName = "test-output-" + new Date().getTime();
        File outputDir = new File(outputDirName);
        outputDir.mkdirs();
        RawFileCollector fileCollector = new RawFileCollector(64,
                new FileNameGenerator(outputDir, "index-", ".zip"), networkClient, errorLogger);

        FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");

        PerThreadBinaryFileAggregatedLogger eventLogger = new PerThreadBinaryFileAggregatedLogger(
                fileNameGenerator1, errorLogger, fileCollector);

        long start = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(12);

        eventLogger.writeEvent(1, 1);

//        eventLogger.shutdown();
        Thread.sleep(5000);

    }

    @Test
    public void classWeaverTest() throws IOException {
        String strTmp = System.getProperty("java.io.tmpdir")  + "/videobug-test-output";
        File outputDir = new File(strTmp);
        outputDir.mkdirs();
        Weaver weaver = new Weaver(outputDir,
                new WeaveConfig("", "", "", ""));

        WeaveLog weaveLog = new WeaveLog(1, 1, 1);
        ClassInfo classInfo = new ClassInfo(
                1, "class-container", "filename",
                "classname", LogLevel.Normal, "hash", "classLoaderIdentifier"
        );

        weaveLog.startMethod(
                "classname", "methodname", "methoddesc", 4,
                "sourceFileName", "methodHash"
        );

        weaveLog.nextDataId(3, 4, EventType.ARRAY_LENGTH,
                Descriptor.Integer, "attributes");

        byte[] weaveBytes = weaver.finishClassProcess(classInfo, weaveLog);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dao = new DataOutputStream(out);
        dao.writeInt(1);
        dao.write(weaveBytes);

        KaitaiInsidiousClassWeaveParser parsedWeaveInfo
                = new KaitaiInsidiousClassWeaveParser(new ByteBufferKaitaiStream(out.toByteArray()));

        assert parsedWeaveInfo.classCount() == 1;
        assert parsedWeaveInfo.classInfo().get(0).classId() == 1;
        assert "classname".equals(parsedWeaveInfo.classInfo().get(0).className().value());
        assert parsedWeaveInfo.classInfo().get(0).methodCount() == 1;
        assert parsedWeaveInfo.classInfo().get(0).probeCount() == 1;
    }

//    @Test
//    public void chronicleQueueTest() throws IOException {
//        try (ChronicleQueue queue = SingleChronicleQueueBuilder.single("queue-dir").build()) {
//            // Obtain an ExcerptAppender
//            ExcerptAppender appender = queue.acquireAppender();
//
//            ClassAliasPool.CLASS_ALIASES.addAlias(UploadFile.class);
//            UploadFileQueue uploadFileQueue = queue.acquireAppender().methodWriter(UploadFileQueue.class);
//            ExcerptTailer tailer = queue.createTailer();
//
//            IErrorLogger logger = new IErrorLogger() {
//                @Override
//                public void log(Throwable t) {
//                    PerThreadBinaryFileAggregatedLoggerTest.this.logger.info("throwable ", t);
//                }
//
//                @Override
//                public void log(String msg) {
//                    PerThreadBinaryFileAggregatedLoggerTest.this.logger.info("message: {}", msg);
//                }
//
//                @Override
//                public void close() {
//
//                }
//            };
//            List<byte[]> classWeavesList = new LinkedList<>();
//            ArchivedIndexWriter archivedIndexWriter = new ArchivedIndexWriter(
//                    new File("Archive.zip"), classWeavesList, logger);
//            FileNameGenerator indexFileNameGenerator = new FileNameGenerator(new File("test"), "index", ".zip");
//            BlockingQueue<UploadFile> fileList = new ArrayBlockingQueue<>(1024);
//            RawFileCollector rawFileCollector = new RawFileCollector(50, indexFileNameGenerator, fileList, logger);
//            UploadFileQueueImpl fileQueue = new UploadFileQueueImpl(rawFileCollector);
//            MethodReader reader = queue.createTailer().methodReader(fileQueue);
//
//            uploadFileQueue.add(new UploadFile("1", 1,
//                            new FilterBuilder(10, 0.01).buildBloomFilter(),
//                            new FilterBuilder(10, 0.01).buildBloomFilter()
//                    )
//            );
//            reader.readOne();
//
////            appender.writeDocument(w -> w.writeBytes(e -> {
////                e.write(new byte[]{1,2,3,4});
////            }));
//
//            // Writes: TestMessage
////            appender.writeText("TestMessage");
//
//
////            ByteBuffer baos = ByteBuffer.allocate(1024);
////            byte[] bytes = new byte[64];
////            tailer.readBytes(b -> {
////                try {
////                    b.copyTo(bytes);
////                } catch (IOException e) {
////                    e.printStackTrace();
////                }
////            });
////            assertEquals(4, bytes[0]);
//
////            byte[] bytes1 = new byte[64];
////            tailer.readBytes(b -> {
////                try {
////                    b.copyTo(bytes1);
////                } catch (IOException e) {
////                    e.printStackTrace();
////                }
////            });
////            assertEquals(5, bytes1[0]);
//
////            assertEquals("TestMessage", tailer.readText());
//        }
//    }

}