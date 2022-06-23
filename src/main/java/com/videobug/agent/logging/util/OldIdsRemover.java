package com.videobug.agent.logging.util;

import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.values.Values;
import org.apache.commons.collections.buffer.CircularFifoBuffer;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class OldIdsRemover implements Runnable {
    private final ChronicleMap<LongValue, LongValue> objectIdContainer;
    private List<Long> recentIdsBuffer;

    public OldIdsRemover(ChronicleMap<LongValue, LongValue> objectIdContainer, List<Long> recentIdsBuffer) {

        this.objectIdContainer = objectIdContainer;
        this.recentIdsBuffer = recentIdsBuffer;
    }

    public void run() {
        Set<Long> set = objectIdContainer.keySet().stream().map(LongValue::getValue).collect(Collectors.toSet());
        set.removeAll(recentIdsBuffer);
        LongValue longVal = Values.newHeapInstance(LongValue.class);
        set.forEach(idToRemove -> {
            longVal.setValue(idToRemove);
            objectIdContainer.remove(longVal);
        });
        longVal.close();
//        System.out.println(objectIdContainer.size() + " keys remain after removing " + recentIdsBuffer.size() + " keys ");
    }
}
