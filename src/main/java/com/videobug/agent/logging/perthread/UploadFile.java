package com.videobug.agent.logging.perthread;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import orestes.bloomfilter.BloomFilter;
import orestes.bloomfilter.json.BloomFilterConverter;

import java.nio.ByteBuffer;

public class UploadFile extends AbstractEvent<UploadFile> {
    private static final JsonParser parser = new JsonParser();
    public String path;
     public long threadId;
    public BloomFilter<Long> valueIdBloomFilter;
    public BloomFilter<Integer> probeIdBloomFilter;

    public UploadFile(String s, long currentThreadId,
                      BloomFilter<Long> valueIdBloomFilter,
                      BloomFilter<Integer> probeIdBloomFilter) {
        this.path = s;
        this.threadId = currentThreadId;
        this.valueIdBloomFilter = valueIdBloomFilter;
        this.probeIdBloomFilter = probeIdBloomFilter;
    }

    public static UploadFile fromBytes(byte[] bytes) {
        ByteBuffer byteReader = ByteBuffer.wrap(bytes);

        String path = BloomFilterUtil.getNextString(byteReader);
        long threadId = byteReader.getLong();
        byte[] valueBytes = BloomFilterUtil.getNextBytes(byteReader);
        byte[] probeBytes = BloomFilterUtil.getNextBytes(byteReader);

        JsonElement valueFilterJson = parser.parse(new String(valueBytes));
        BloomFilter<Long> valueFilter = BloomFilterConverter.fromJson(valueFilterJson, Long.class); //{"size":240,"hashes":4,"HashMethod":"MD5","bits":"AAAAEAAAAACAgAAAAAAAAAAAAAAQ"}

        JsonElement probeFilterJson = parser.parse(new String(probeBytes));
        BloomFilter<Integer> probeFilter = BloomFilterConverter.fromJson(probeFilterJson, Integer.class); //{"size":240,"hashes":4,"HashMethod":"MD5","bits":"AAAAEAAAAACAgAAAAAAAAAAAAAAQ"}

        return new UploadFile(path, threadId, valueFilter, probeFilter);

    }

    public byte[] toBytes() {
        byte[] valueBytes = BloomFilterConverter.toJson(valueIdBloomFilter).toString().getBytes();
        byte[] probeBytes = BloomFilterConverter.toJson(probeIdBloomFilter).toString().getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(4 + path.length() + 8 + 4 + valueBytes.length + 4 + probeBytes.length);
        buffer.putInt(path.length());
        buffer.put(path.getBytes());
        buffer.putLong(threadId);


        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);

        buffer.putInt(probeBytes.length);
        buffer.put(probeBytes);
        return buffer.array();
    }

    @Override
    public void writeMarshallable(BytesOut out) {
        super.writeMarshallable(out);
        if (PREGENERATED_MARSHALLABLE) {
            out.writeStopBit(MASHALLABLE_VERSION);
            out.write(toBytes());
        }
    }
    private static final int MASHALLABLE_VERSION = 1;
    @Override
    public void readMarshallable(BytesIn in) {
        super.readMarshallable(in);
        if (PREGENERATED_MARSHALLABLE) {
            int version = (int) in.readStopBit();
            if (version == MASHALLABLE_VERSION) {
                byte[] bytes = in.toByteArray();
                copyFrom(fromBytes(bytes));
            }
        }
    }

    @Override
    public void writeMarshallable(WireOut out) {
        super.writeMarshallable(out);
        if (PREGENERATED_MARSHALLABLE) {
            out.write("bytes").bytes(toBytes());
        }
    }

    @Override
    public void readMarshallable(WireIn in) {
        super.readMarshallable(in);
        if (PREGENERATED_MARSHALLABLE) {
            byte[] bytes = in.read("bytes").bytes();
            UploadFile uf = fromBytes(bytes);
            copyFrom(uf);
        }
    }

    private void copyFrom(UploadFile that) {
        this.path = that.path;
        this.threadId = that.threadId;
        this.valueIdBloomFilter = that.valueIdBloomFilter;
        this.probeIdBloomFilter = that.probeIdBloomFilter;
    }
}