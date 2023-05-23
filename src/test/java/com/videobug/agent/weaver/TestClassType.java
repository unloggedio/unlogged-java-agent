package com.videobug.agent.weaver;

import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.Logging;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import com.videobug.agent.logging.perthread.RawFileCollector;
import com.videobug.agent.logging.util.FileNameGenerator;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestClassType {

    @Test
    public void testTypes() throws IOException {

        WeaveConfig config = new WeaveConfig(new RuntimeWeaverParameters(""));
        WeaveClassLoader loader = new WeaveClassLoader(config);

        File outputDir = new File("./test/");
        outputDir.mkdirs();
        Weaver weaver = new Weaver(outputDir, config);

        FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
        RawFileCollector fileCollector = new RawFileCollector(64,
                new FileNameGenerator(outputDir, "index-", ".zip"), null, weaver, outputDir);
        PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, weaver, fileCollector);

        IEventLogger eventLogger = Logging.initialiseAggregatedLogger(
                weaver, perThreadBinaryFileAggregatedLogger, outputDir);
//        EventIterator iterator = new EventIterator(eventLogger, loader.getWeaveLog());

        eventLogger.recordEvent(1, 8748956);
        Integer integer = 9837534;
        Long value = 8748956293485L;
        eventLogger.recordEvent(1, value);
        eventLogger.recordEvent(1, integer);

    }
}
