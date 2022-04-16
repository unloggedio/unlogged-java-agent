package com.videobug.agent.weaver;

import com.insidious.common.weaver.DataInfo;
import com.insidious.common.weaver.Descriptor;
import com.insidious.common.weaver.EventType;
import org.junit.Assert;
import org.junit.Test;

public class DataInfoTest {

	/**
	 * Create an object and test getAttribute method
	 */
	@Test
	public void testAttributes() {
		DataInfo entry = new DataInfo(0, 1, 2, 3, 4, EventType.CALL,
				Descriptor.Object, "Desc=(IJ)Ljava/lang/Object;,Type=1,Empty=,Property=2");
		Assert.assertEquals("(IJ)Ljava/lang/Object;", entry.getAttribute("Desc", ""));
		Assert.assertEquals("1", entry.getAttribute("Type", ""));
		Assert.assertEquals("2", entry.getAttribute("Property", ""));
		Assert.assertEquals("", entry.getAttribute("Empty", "1"));
		Assert.assertEquals("", entry.getAttribute("NotExist", ""));
	}
	
	/**
	 * Create an object and test getAttribute method.
	 * The test cases come from actual DataInfo.
	 */
	@Test
	public void testActualAttributes() {
		DataInfo entry = new DataInfo(0, 1, 2, 3, 4, EventType.CALL, Descriptor.Object, "Name=addDesc,Desc=(Ljava/lang/String;Ljava/lang/String;)V");
		Assert.assertEquals("(Ljava/lang/String;Ljava/lang/String;)V", entry.getAttribute("Desc", ""));

		DataInfo entry2 = new DataInfo(0, 1, 2, 3, 4, EventType.CALL, Descriptor.Object, "Name=addDesc,Desc=(Ljava/lang/String;Ljava/lang/String;)V,Desc2=");
		Assert.assertEquals("(Ljava/lang/String;Ljava/lang/String;)V", entry2.getAttribute("Desc", ""));
	}
}
