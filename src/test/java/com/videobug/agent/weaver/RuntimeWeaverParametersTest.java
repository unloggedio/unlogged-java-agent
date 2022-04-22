package com.videobug.agent.weaver;

import static org.junit.Assert.*;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.Assert;
import org.junit.Test;


public class RuntimeWeaverParametersTest {

    @Test
    public void testArgs() {
        RuntimeWeaverParameters params = new RuntimeWeaverParameters("format=omni,dump=true,output=selogger-output-1");
        assertFalse(params.isOutputJsonEnabled());
        assertTrue(params.isDumpClassEnabled());
        assertEquals("selogger-output-1", params.getOutputDirname());
        Assert.assertEquals(RuntimeWeaver.Mode.Stream, params.getMode());


        String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
        params = new RuntimeWeaverParameters("output=selogger-output-{time}-example");
        assertNotEquals("selogger-output-{time}-example", params.getOutputDirname());
        assertTrue(params.getOutputDirname().contains(today));

        params = new RuntimeWeaverParameters("output=selogger-output-{time:}-example");
        assertEquals("selogger-output-{time:}-example", params.getOutputDirname());

        params = new RuntimeWeaverParameters("output=selogger-output-{time:yyyyMMdd}-example");
        assertTrue(params.getOutputDirname().startsWith("selogger-output-" + today) &&
                params.getOutputDirname().endsWith("-example"));

        params = new RuntimeWeaverParameters("output={time:yyyyMMdd}");
        assertTrue(params.getOutputDirname().startsWith(today));
    }

}