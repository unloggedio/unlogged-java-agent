package com.videobug.agent.weaver;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import static com.videobug.agent.weaver.WeaveConfig.KEY_RECORD_ALL;


public class WeaverNoneTest {

    @Test
    public void testLine() throws IOException {
        // Execute a weaving
        WeaveConfig config = new WeaveConfig(new RuntimeWeaverParameters(KEY_RECORD_ALL));
        WeaveClassLoader loader = new WeaveClassLoader(config);
        Class<?> wovenClass = loader.loadAndWeaveClass("com.videobug.agent.testdata.DivideClass");
        try {
            wovenClass.getConstructors()[0].newInstance();
        } catch (InvocationTargetException | IllegalAccessException | InstantiationException e) {
            Assert.fail();
        }
    }


}
