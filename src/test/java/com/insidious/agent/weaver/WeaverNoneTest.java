package com.insidious.agent.weaver;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.junit.Assert;
import org.junit.Test;



public class WeaverNoneTest {

	@Test
	public void testLine() throws IOException {
		// Execute a weaving 
		WeaveConfig config = new WeaveConfig(WeaveConfig.KEY_RECORD_NONE, "localhost:9921", "username", "password");
		WeaveClassLoader loader = new WeaveClassLoader(config);
		Class<?> wovenClass = loader.loadAndWeaveClass("com.insidious.agent.testdata.DivideClass");
		try {
			wovenClass.getConstructors()[0].newInstance(null);
		} catch (InvocationTargetException|IllegalAccessException|InstantiationException e) {
			Assert.fail();
		}
	}

	
}
