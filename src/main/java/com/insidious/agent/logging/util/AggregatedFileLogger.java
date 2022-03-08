package com.insidious.agent.logging.util;

public interface AggregatedFileLogger {
    void writeNewObjectType(long id, long typeId);

    void writeNewString(long id, String stringObject);

    void writeNewException(byte[] toString);

    void writeEvent(int id, long value);

    void writeHostname();

    void writeTimestamp();

    void writeNewTypeRecord(String toString);

    void writeWeaveInfo(byte[] byteArray);
}
