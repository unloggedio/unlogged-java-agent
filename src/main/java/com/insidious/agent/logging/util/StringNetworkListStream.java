package com.insidious.agent.logging.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.rsocket.RSocket;
import io.rsocket.util.DefaultPayload;

import java.io.*;
import java.util.zip.GZIPOutputStream;

/**
 * A utility class to write strings to files.
 */
public class StringNetworkListStream {

    private final RSocket rSocket;
    private long itemCount;
    private boolean compress;

    /**
     * @param rSocket  specifies a file name generator for files to be written.
     *                 It should be a sufficiently large number.
     */
    public StringNetworkListStream(RSocket rSocket) {
        this.rSocket = rSocket;
        this.itemCount = 0;

    }

    /**
     * Write a string.
     *
     * @param s is a String.
     */
    public void write(String s) {
        ByteBuf out = ByteBufAllocator.DEFAULT.buffer();
        out.writeBytes(s.getBytes());
        rSocket.fireAndForget(DefaultPayload.create(out, null));
    }

    /**
     * Output strings in the internal buffer to a file,
     * and then close the stream.
     */
    public synchronized void close() {
        rSocket.dispose();
    }

}
