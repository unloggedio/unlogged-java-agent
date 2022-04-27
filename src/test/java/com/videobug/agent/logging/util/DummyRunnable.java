package com.videobug.agent.logging.util;

import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;

public class DummyRunnable implements Runnable {
    private final int i;
    private final PerThreadBinaryFileAggregatedLogger eventLogger;

    public DummyRunnable(int i, PerThreadBinaryFileAggregatedLogger eventLogger) {
        this.i = i;
        this.eventLogger = eventLogger;
    }

    @Override
    public void run() {
        eventLogger.writeEvent(i, i * 2);
        eventLogger.writeNewTypeRecord(i * 3, "hello-type-" + i * 3, "therecord".getBytes());
        eventLogger.writeNewString(i * 4, "hello-string-" + i * 4);
        eventLogger.writeNewObjectType(i * 5, i * 3);
    }

}
