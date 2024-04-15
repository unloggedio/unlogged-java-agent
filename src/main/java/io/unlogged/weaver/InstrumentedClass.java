package io.unlogged.weaver;

public class InstrumentedClass {
    byte[] bytes;
    byte[] classWeaveInfo;

    public InstrumentedClass(byte[] bytes, byte[] classWeaveInfo) {
        this.bytes = bytes;
        this.classWeaveInfo = classWeaveInfo;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public byte[] getClassWeaveInfo() {
        return classWeaveInfo;
    }
}