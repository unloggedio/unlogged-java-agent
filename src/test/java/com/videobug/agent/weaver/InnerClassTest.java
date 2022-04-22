package com.videobug.agent.weaver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import com.insidious.common.weaver.Descriptor;
import com.insidious.common.weaver.EventType;
import com.videobug.agent.logging.Logging;
import com.videobug.agent.logging.io.MemoryLogger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for inner class
 */
public class InnerClassTest {

    private Class<?> wovenClass;
    private Class<?> ownerClass;
    private MemoryLogger memoryLogger;
    private EventIterator it;

    /**
     * Execute a weaving for a class and define them as Java classes
     */
    @Before
    public void setup() throws IOException, ClassNotFoundException {
        // Load the woven class
        WeaveConfig config = new WeaveConfig(WeaveConfig.KEY_RECORD_DEFAULT, "localhost:9921", "username", "password");
        WeaveClassLoader loader = new WeaveClassLoader(config);
        wovenClass = loader.loadAndWeaveClass("com.videobug.agent.testdata.SimpleTarget$StringComparator");
        ownerClass = loader.loadClassFromResource("com.videobug.agent.testdata.SimpleTarget", "com/videobug/agent/testdata/SimpleTarget.class");

        memoryLogger = Logging.initializeForTest();
        it = new EventIterator(memoryLogger, loader.getWeaveLog());
    }

    /**
     * Remove woven class definition from memory
     */
    @After
    public void tearDown() {
        wovenClass = null;
        ownerClass = null;
    }

    /**
     * Execute a constructor of the woven class and
     * check the correctness of the observed events
     */
    @Test
    public void testSort() throws IOException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        // Create an instance of woven class
        Constructor<?> c = wovenClass.getConstructor(ownerClass);
        Object owner = ownerClass.getDeclaredConstructor().newInstance();
        Object o = c.newInstance(owner);

        // Check the correctness of the recorded event sequence
        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.METHOD_ENTRY, it.getEventType());
        Assert.assertEquals("com/videobug/agent/testdata/SimpleTarget$StringComparator", it.getClassName());
        Assert.assertEquals("<init>", it.getMethodName());

        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.METHOD_PARAM, it.getEventType());
        Assert.assertSame(owner, it.getObjectValue());

        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.PUT_INSTANCE_FIELD_BEFORE_INITIALIZATION, it.getEventType());
        Assert.assertSame(owner, it.getObjectValue());

        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.CALL, it.getEventType());
        Assert.assertTrue(it.getAttributes().contains("java/lang/Object"));
        Assert.assertTrue(it.getAttributes().contains("<init>"));

        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.CALL_RETURN, it.getEventType());
        Assert.assertEquals(Descriptor.Void, it.getDataIdValueDesc());

        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.METHOD_OBJECT_INITIALIZED, it.getEventType());
        Assert.assertSame(o, it.getObjectValue());

        Assert.assertTrue(it.next());
        Assert.assertEquals(EventType.METHOD_NORMAL_EXIT, it.getEventType());

        Assert.assertFalse(it.next());
    }

}
