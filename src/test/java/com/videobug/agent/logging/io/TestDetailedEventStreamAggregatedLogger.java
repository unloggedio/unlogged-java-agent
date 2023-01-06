package com.videobug.agent.logging.io;

import com.videobug.agent.logging.Logging;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import com.videobug.agent.logging.perthread.RawFileCollector;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.weaver.WeaveClassLoader;
import com.videobug.agent.weaver.WeaveConfig;
import com.videobug.agent.weaver.Weaver;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// todo : complete this test
public class TestDetailedEventStreamAggregatedLogger {
    private Optional<?> createAOptionalValue() {
        List<Integer> list = new ArrayList<>();
        list.add(4);
        list.add(3);
        list.add(1);
        list.add(5);
        return Optional.of(list);
    }

    private DetailedEventStreamAggregatedLogger createDetailedLogger() throws IOException {
        WeaveConfig config = new WeaveConfig(WeaveConfig.KEY_RECORD_EXEC,
                "localhost:9921", "username", "password");
        WeaveClassLoader loader = new WeaveClassLoader(config);

        File outputDir = new File("./test/");
        outputDir.mkdirs();
        Weaver weaver = new Weaver(outputDir, config);

        FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
        RawFileCollector fileCollector = new RawFileCollector(64,
                new FileNameGenerator(outputDir, "index-", ".zip"), null, weaver);
        PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, weaver, fileCollector);

        DetailedEventStreamAggregatedLogger detailedLogger = Logging.initialiseDetailedAggregatedLogger(
                "com.some.package", perThreadBinaryFileAggregatedLogger, outputDir);
        return detailedLogger;
    }

    @Test
    @Ignore
    public void recordOptionalObject_serializationShouldContainActualValue() {
//        Object value = createAOptionalValue();
        List<Integer> list = new ArrayList<>();
        list.add(4);
        list.add(3);
        list.add(1);
        list.add(5);

        DetailedEventStreamAggregatedLogger detailedLogger;
        try {
            detailedLogger = createDetailedLogger();
            int dataId = 12;
            detailedLogger.recordEvent(dataId, list);

        } catch (Exception e) {
            System.out.println("failed to create detailed logger!");
        }


    }
}