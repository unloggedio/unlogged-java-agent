package io.unlogged.weaver;

import com.insidious.common.weaver.EventType;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DataInfoProvider {

    private final AtomicInteger classId;
    private final AtomicInteger methodId;
    private final AtomicInteger probeId;
    private OutputStream probeOutputStream;
    private File idsInfoOutputFile;

    public DataInfoProvider(int classId, int methodId, int probeId) {
        this.classId = new AtomicInteger(classId);
        this.methodId = new AtomicInteger(methodId);
        this.probeId = new AtomicInteger(probeId);
    }

    public int nextClassId() {
        return classId.addAndGet(1);
    }

    public int nextMethodId() {
        return methodId.addAndGet(1);
    }

    public int nextProbeId(EventType eventType) {
        int nextProbeId = probeId.addAndGet(1);
        if (
                eventType.equals(EventType.CALL_PARAM) ||
                        eventType.equals(EventType.METHOD_PARAM) ||
                        eventType.equals(EventType.LABEL) ||
                        eventType.equals(EventType.CALL_RETURN) ||
                        eventType.equals(EventType.METHOD_NORMAL_EXIT) ||
                        eventType.equals(EventType.METHOD_EXCEPTIONAL_EXIT)
        ) {
//            System.out.println("Probe to record: " + nextProbeId);
            byte[] buffer = new byte[4];
            buffer[0] = (byte) (nextProbeId >>> 24);
            buffer[1] = (byte) (nextProbeId >>> 16);
            buffer[2] = (byte) (nextProbeId >>> 8);
            buffer[3] = (byte) (nextProbeId >>> 0);
            try {
                probeOutputStream.write(buffer);
            } catch (IOException e) {
                // should never happen
            }
        }
        return nextProbeId;
    }

    public OutputStream getProbeOutputStream() {
        return probeOutputStream;
    }

    public void setProbeOutputStream(OutputStream probeOutputStream) {
        this.probeOutputStream = probeOutputStream;
    }

    public void setIdsInfoFile(File idsInfoOutputFile) {
        this.idsInfoOutputFile = idsInfoOutputFile;
    }

    public void flushIdInformation() throws IOException {
//        DataOutputStream infoWriter = new DataOutputStream(new FileOutputStream(idsInfoOutputFile));
//        infoWriter.writeInt(classId.get());
//        infoWriter.writeInt(methodId.get());
//        infoWriter.writeInt(probeId.get());
    }
}