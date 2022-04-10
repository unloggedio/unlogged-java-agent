package com.videobug.agent.logging.perthread;

import orestes.bloomfilter.BloomFilter;
import orestes.bloomfilter.FilterBuilder;

public class BloomFilterUtil {

    public static BloomFilter<Long> newBloomFilterForValues() {
        return new FilterBuilder(1024 * 16, 0.01).buildBloomFilter();
    }

    public static BloomFilter<Integer> newBloomFilterForProbes() {
        return new FilterBuilder(1024 * 16, 0.01).buildBloomFilter();
    }

}
