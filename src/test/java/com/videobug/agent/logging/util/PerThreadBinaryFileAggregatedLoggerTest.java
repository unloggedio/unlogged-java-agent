package com.videobug.agent.logging.util;

import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

public class PerThreadBinaryFileAggregatedLoggerTest {


    private Logger logger = LoggerFactory.getLogger(PerThreadBinaryFileAggregatedLoggerTest.class);

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
        PerThreadBinaryFileAggregatedLogger logger = new PerThreadBinaryFileAggregatedLogger(
                "test-output-" + new Date().getTime(), errorLogger,
                "token", "sessionId", "serverAddress", 100);

        for (int i = 0; i < 100000; i++) {
            logger.writeEvent(i, i);
        }

        Thread.sleep(20000);
        logger.shutdown();
        Thread.sleep(3000);

    }

}