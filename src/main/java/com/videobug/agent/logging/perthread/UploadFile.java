package com.videobug.agent.logging.perthread;

import orestes.bloomfilter.BloomFilter;

class UploadFile {
    final public String path;
    final public long threadId;
    final public BloomFilter<Long> valueIdBloomFilter;
    final public BloomFilter<Integer> probeIdBloomFilter;

    public UploadFile(String s, long currentThreadId,
                      BloomFilter<Long> valueIdBloomFilter,
                      BloomFilter<Integer> probeIdBloomFilter) {
        this.path = s;
        this.threadId = currentThreadId;
        this.valueIdBloomFilter = valueIdBloomFilter;
        this.probeIdBloomFilter = probeIdBloomFilter;
    }
}