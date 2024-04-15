package io.unlogged.weaver;

import static org.junit.Assert.*;

import java.text.SimpleDateFormat;
import java.util.Date;

import io.unlogged.Runtime;
import org.junit.Assert;
import org.junit.Test;


public class WeaveParametersTest {

    @Test
    public void testArgs() {
        WeaveParameters params = new WeaveParameters("format=omni,dump=true,output=selogger-output-1");
        assertFalse(params.isOutputJsonEnabled());
        assertTrue(params.isDumpClassEnabled());
        assertEquals("selogger-output-1", params.getOutputDirname());
        Assert.assertEquals(Runtime.Mode.STREAM, params.getMode());


        String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
        params = new WeaveParameters("output=selogger-output-{time}-example");
        assertNotEquals("selogger-output-{time}-example", params.getOutputDirname());
        assertTrue(params.getOutputDirname().contains(today));

        params = new WeaveParameters("output=selogger-output-{time:}-example");
        assertEquals("selogger-output-{time:}-example", params.getOutputDirname());

        params = new WeaveParameters("output=selogger-output-{time:yyyyMMdd}-example");
        assertTrue(params.getOutputDirname().startsWith("selogger-output-" + today) &&
                params.getOutputDirname().endsWith("-example"));

        params = new WeaveParameters("output={time:yyyyMMdd}");
        assertTrue(params.getOutputDirname().startsWith(today));
    }

}
