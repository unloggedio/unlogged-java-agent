package com.insidious.agent.rsocket;

import com.insidious.agent.logging.Logging;
import com.insidious.agent.logging.util.BinaryFileAggregatedLogger;
import com.insidious.agent.weaver.WeaveClassLoader;
import com.insidious.agent.weaver.WeaveConfig;
import com.insidious.agent.weaver.Weaver;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Hooks;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.IllegalClassFormatException;

public class NetworkEventsTest {

    Logger logger = LoggerFactory.getLogger(NetworkEventsTest.class);

    @Test
    public void testNetworkEvents() throws IllegalClassFormatException, IOException, InstantiationException, IllegalAccessException {
        String agentArgs = "output=./selogger-{time},size=64,i=com/artbrain,weave=ALL,format=network,server=localhost:9921,username=user,password=password";

        Hooks.onErrorDropped(error -> {
            logger.error("Exception happened:", error);
        });

        String resourceName = "com.insidious.agent.rsocket.TestTarget";

        WeaveConfig weaveConfig = new WeaveConfig(agentArgs, "localhost:9921", "user", "password");

        File testOutputDir = new File("./test-output");

        if (!testOutputDir.exists()) {
            testOutputDir.mkdirs();
        }

        Weaver weaver = new Weaver(testOutputDir, weaveConfig);
        WeaveClassLoader weaveClassLoader = new WeaveClassLoader(weaveConfig);


        String token = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ1c2VyIiwicHJvamVjdElkIjoiNjFhMGRiMzBhMTgwNDM1MzViNTdjMTUwIiwiaWF0IjoxNjQxOTIzNDc5LCJleHAiOjE2NDI0MjQxMzN9.IdSE0Ond_3pmerb5Rg9Tzaw2ZFL_CF3XgK2g4VEBpEZ_XDnGEz0Z2JSm5rt6Ksw5yu-MEePw0xIHJYwymM6VfA";
        BinaryFileAggregatedLogger binaryFileAggregatedLogger = new BinaryFileAggregatedLogger("test", weaver, token, "session-1", "http://localhost:8080");
        Logging.initialiseAggregatedLogger(weaver, binaryFileAggregatedLogger);
        Class<?> testTargetClass = weaveClassLoader.loadAndWeaveClass(resourceName);

        Runnable targetInstance = (Runnable) testTargetClass.newInstance();
        targetInstance.run();


    }
}
