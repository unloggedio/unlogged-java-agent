package com.videobug.agent.logging.util;

import com.insidious.common.parser.KaitaiInsidiousClassWeaveParser;
import com.insidious.common.weaver.ClassInfo;
import com.insidious.common.weaver.Descriptor;
import com.insidious.common.weaver.EventType;
import com.insidious.common.weaver.LogLevel;
import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.Logging;
import com.videobug.agent.logging.io.EventStreamAggregatedLogger;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import com.videobug.agent.logging.perthread.RawFileCollector;
import com.videobug.agent.weaver.RuntimeWeaverParameters;
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
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PerThreadBinaryFileAggregatedLoggerTest {


    private final Logger logger = LoggerFactory.getLogger(PerThreadBinaryFileAggregatedLoggerTest.class);

    @Test
    public void testLoggerIndex1() throws InterruptedException, IOException {
        IErrorLogger errorLogger = new IErrorLogger() {
            @Override
            public void log(Throwable throwable) {
                logger.error("", throwable);

            }

            @Override
            public void log(String message) {
                logger.info(message);
            }

            @Override
            public void close() {

            }
        };

        NetworkClient networkClient = new NetworkClient("serverAddress",
                "sessionId", "token", errorLogger);

        String outputDirName = "" +
                "test-output-" + new Date().getTime();

        File outputDir = new File(outputDirName);
        outputDir.mkdirs();
        RawFileCollector fileCollector = new RawFileCollector(64,
                new FileNameGenerator(outputDir, "index-", ".zip"), networkClient, errorLogger, outputDir);

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

//        Thread.sleep(500);
        eventLogger.shutdown();
//        Thread.sleep(500);

        File[] fileToDelete = outputDir.listFiles();
        for (File file : fileToDelete) {
            file.delete();
        }
        outputDir.delete();


    }

    @Test
    public void testLoggerIndex2() throws InterruptedException, IOException {
        IErrorLogger errorLogger = new IErrorLogger() {
            @Override
            public void log(Throwable throwable) {
                logger.error("", throwable);

            }

            @Override
            public void log(String message) {
                logger.info(message);
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
                new FileNameGenerator(outputDir, "index-", ".zip"), networkClient, errorLogger, outputDir);

        FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");

        PerThreadBinaryFileAggregatedLogger eventLogger = new PerThreadBinaryFileAggregatedLogger(
                fileNameGenerator1, errorLogger, fileCollector);

        long start = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(12);

        eventLogger.writeEvent(1, 1);

//        eventLogger.shutdown();
//        Thread.sleep(100);
        File[] files = outputDir.listFiles();
        for (File file : files) {
            file.delete();
        }
        outputDir.delete();


    }

    @Test
    public void classWeaverTest() throws IOException {
        String strTmp = System.getProperty("java.io.tmpdir") + "/videobug-test-output";
        File outputDir = new File(strTmp);
        outputDir.mkdirs();
        Weaver weaver = new Weaver(outputDir, new WeaveConfig(new RuntimeWeaverParameters("")));

        WeaveLog weaveLog = new WeaveLog(1, 1, 1);
        ClassInfo classInfo = new ClassInfo(
                1, "class-container", "filename",
                "classname", LogLevel.Normal, "hash", "classLoaderIdentifier",
                new String[]{}, "supername", "signature"
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
        File[] files = outputDir.listFiles();
        for (File file : files) {
            file.delete();
        }
        outputDir.delete();

    }

    @Test
    public void objectIdGeneratorTest() throws IOException {

        RuntimeWeaverParameters params = new RuntimeWeaverParameters("");

        File outputDir = new File(params.getOutputDirname());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        WeaveConfig config = new WeaveConfig(params);
        Weaver weaver = new Weaver(outputDir, config);

        NetworkClient networkClient = new NetworkClient(params.getServerAddress(),
                config.getSessionId(), params.getAuthToken(), weaver);

        FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");
        RawFileCollector fileCollector = new RawFileCollector(params.getFilesPerIndex(), fileNameGenerator1,
                networkClient, weaver,
                outputDir);

        outputDir.mkdirs();
        FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
        PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, weaver, fileCollector);

        perThreadBinaryFileAggregatedLogger.writeEvent(1, 2);
        EventStreamAggregatedLogger aggergatedLogger =
                Logging.initialiseAggregatedLogger(weaver, perThreadBinaryFileAggregatedLogger, outputDir);

        int iterationCount = 1024 * 1024;
        long start = System.nanoTime();
        long end = 0, totalTimeSeconds = 0, iterationsPerSecond = 0;
        for (int i = 0; i < iterationCount; i++) {
            aggergatedLogger.recordEvent(i, new PerThreadBinaryFileAggregatedLoggerTest());
        }
        end = System.nanoTime();
        totalTimeSeconds = (end - start) / (1000 * 1000);
        iterationsPerSecond = iterationCount / totalTimeSeconds;
        System.out.println("getId: [" + iterationsPerSecond + "] iter/second");

//        start = System.nanoTime();
//        for (int i = 0; i < iterationCount; i++) {
//            aggergatedLogger.recordEventSynchronized(i, new PerThreadBinaryFileAggregatedLoggerTest());
//        }
//        end = System.nanoTime();
//        totalTimeSeconds = (end - start) / (1000 * 1000);
//        iterationsPerSecond = iterationCount / totalTimeSeconds;
//        System.out.println("getId: [" + iterationsPerSecond + "] iter/second");

//        ObjectIdAggregatedStream objectIdMap = aggergatedLogger.getObjectIdMap();
//        assert objectIdMap.size() > 0;
//        assert objectIdMap.capacity() > 0;

        File[] files = outputDir.listFiles();
        for (File file : files) {
            file.delete();
        }
        outputDir.delete();


    }

    @Test
    public void testObjectIdMap() throws IOException {
        int iterationCount = 1024 * 1024 * 10;
        ObjectIdMap objectIdMap = new ObjectIdMap(1024 * 1024 * 1, new File("."));
        long start = 0;
        long end = 0, totalTimeSeconds = 0, iterationsPerSecond = 0;

        HashMap<Long, Boolean> idMap = new HashMap<Long, Boolean>();
        int clashCount = 0;

//        start = System.nanoTime();
//        for (int i = 0; i < iterationCount; i++) {
//            long newId = objectIdMap.getId(new Object());
//            if (idMap.containsKey(newId)) {
//                clashCount++;
//            }
//            idMap.put(newId, true);
//        }
//        end = System.nanoTime();
//        totalTimeSeconds = (end - start) / (1000 * 1000);
//        iterationsPerSecond = iterationCount / totalTimeSeconds;
//        System.out.println("getIdChronicle: [" + iterationsPerSecond + "] iter/second - had " + clashCount + " clashes");

        objectIdMap.close();

        objectIdMap = new ObjectIdMap(1024 * 1024 * 10, new File("."));
        start = System.nanoTime();
        idMap = new HashMap<>();
        clashCount = 0;
        for (int i = 0; i < iterationCount; i++) {
            long newId = objectIdMap.getId(new Object());
            if (idMap.containsKey(newId)) {
                clashCount++;
            }
            idMap.put(newId, true);
        }
        end = System.nanoTime();
        totalTimeSeconds = (end - start) / (1000 * 1000);
        iterationsPerSecond = iterationCount / totalTimeSeconds;
        System.out.println(
                "getIdChronicle: [" + iterationsPerSecond + "] iter/second - had " + clashCount + " clashes");


    }


}