package com.videobug.agent.logging.perthread;

import java.io.OutputStream;
import java.util.Map;
import java.util.function.Function;

class FileEventCountThresholdChecker implements Runnable {

    private final Map<Integer, OutputStream> threadFileMap;
    private final ThreadEventCountProvider threadEventCountProvider;
    private final Function<Integer, Void> onExpiryRunner;

    public FileEventCountThresholdChecker(
            Map<Integer, OutputStream> threadFileMap,
            ThreadEventCountProvider threadEventCountProvider,
            Function<Integer, Void> onExpiryRunner
    ) {
        assert onExpiryRunner != null;
        this.threadEventCountProvider = threadEventCountProvider;
        this.threadFileMap = threadFileMap;
        this.onExpiryRunner = onExpiryRunner;
    }

    @Override
    public void run() {
        Integer[] keySet = threadFileMap.keySet().toArray(new Integer[0]);
        for (Integer theThreadId : keySet) {
            int eventCount = threadEventCountProvider.getThreadEventCount(theThreadId).get();
            if (eventCount > 0) {
                onExpiryRunner.apply(theThreadId);
            }
        }
    }

}