package com.insidious.agent.weaver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.metadata.*;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * This object manages options passed to the weaver.
 * This configuration controls the entire weaving process.
 */
public class WeaveConfig {

    private Integer processId;
    private String sessionId;
    private RSocket rSocket;
    private boolean weaveExec = true;
    private boolean weaveMethodCall = true;
    private boolean weaveFieldAccess = true;
    private boolean weaveArray = true;
    private boolean weaveLabel = true;
    private boolean weaveSynchronization = true;
    private boolean weaveParameters = true;
    private boolean weaveLocalAccess = true;
    private boolean weaveObject = true;
    private boolean weaveLineNumber = true;
    private boolean ignoreArrayInitializer = false;

    private boolean weaveNone = false;

    public static final String KEY_RECORD_DEFAULT = "";
    public static final String KEY_RECORD_ALL = "ALL";
    public static final String KEY_RECORD_DEFAULT_PLUS_LOCAL = "EXEC+CALL+FIELD+ARRAY+SYNC+OBJECT+PARAM+LOCAL";
    private static final String KEY_RECORD = "Events";
    private static final String KEY_RECORD_SEPARATOR = ",";
    public static final String KEY_RECORD_NONE = "NONE";

    public static final String KEY_RECORD_EXEC = "EXEC";
    public static final String KEY_RECORD_CALL = "CALL";
    public static final String KEY_RECORD_FIELD = "FIELD";
    public static final String KEY_RECORD_ARRAY = "ARRAY";
    public static final String KEY_RECORD_SYNC = "SYNC";
    public static final String KEY_RECORD_OBJECT = "OBJECT";
    public static final String KEY_RECORD_LABEL = "LABEL";
    public static final String KEY_RECORD_PARAMETERS = "PARAM";
    public static final String KEY_RECORD_LOCAL = "LOCAL";
    public static final String KEY_RECORD_LINE = "LINE";

    private static Integer getProcessId(final Integer fallback) {
        // Note: may fail in some JVM implementations
        // therefore fallback has to be provided

        // something like '<pid>@<hostname>', at least in SUN / Oracle JVMs
        final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
        final int index = jvmName.indexOf('@');

        if (index < 1) {
            // part before '@' empty (index = 0) / '@' not found (index = -1)
            return fallback;
        }

        try {
            return Integer.parseInt(jvmName.substring(0, index));
        } catch (NumberFormatException e) {
            // ignore
        }
        return fallback;
    }


    /**
     * Construct a configuration from string
     *
     * @param options       specify a string including: EXEC, CALL, FIELD, ARRAY, SYNC, OBJECT, LABEL, PARAM, LOCAL, and NONE.
     * @param serverAddress
     * @return true if at least one weaving option is enabled (except for parameter recording).
     */
    public WeaveConfig(String options, String serverAddress) {
        String opt = options.toUpperCase();
        System.out.printf("Recording option: [%s] Server Address [%s]\n", opt, serverAddress);
        if (opt.equals(KEY_RECORD_ALL)) {
            opt = KEY_RECORD_EXEC + KEY_RECORD_CALL + KEY_RECORD_FIELD + KEY_RECORD_ARRAY + KEY_RECORD_SYNC + KEY_RECORD_OBJECT + KEY_RECORD_PARAMETERS + KEY_RECORD_LABEL + KEY_RECORD_LOCAL + KEY_RECORD_LINE;
        } else if (opt.equals(KEY_RECORD_DEFAULT)) {
            opt = KEY_RECORD_EXEC + KEY_RECORD_CALL + KEY_RECORD_FIELD + KEY_RECORD_ARRAY + KEY_RECORD_SYNC + KEY_RECORD_OBJECT + KEY_RECORD_PARAMETERS;
        } else if (opt.equals(KEY_RECORD_NONE)) {
            opt = "";
            weaveNone = true;
        }
        weaveExec = opt.contains(KEY_RECORD_EXEC);
        weaveMethodCall = opt.contains(KEY_RECORD_CALL);
        weaveFieldAccess = opt.contains(KEY_RECORD_FIELD);
        weaveArray = opt.contains(KEY_RECORD_ARRAY);
        weaveSynchronization = opt.contains(KEY_RECORD_SYNC);
        weaveLabel = opt.contains(KEY_RECORD_LABEL);
        weaveParameters = opt.contains(KEY_RECORD_PARAMETERS);
        weaveLocalAccess = opt.contains(KEY_RECORD_LOCAL);
        weaveObject = opt.contains(KEY_RECORD_OBJECT);
        weaveLineNumber = opt.contains(KEY_RECORD_LINE);
        ignoreArrayInitializer = false;

        this.sessionId = UUID.randomUUID().toString();
        this.processId = getProcessId(new Random().nextInt());

        if (serverAddress != null && serverAddress.length() > 0) {
            String[] addressParts = serverAddress.split(":");

            int addressPort = 80;
            if (addressParts.length > 1) {
                addressPort = Integer.parseInt(addressParts[1]);
            }

            System.out.printf("Creating network logger at [%s]: %s:%s\n\n", serverAddress, addressParts[0], addressPort);


            RSocketConnector connector = RSocketConnector.create();
            connector.metadataMimeType(WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.getString());
            connector.dataMimeType(WellKnownMimeType.MESSAGE_RSOCKET_COMPOSITE_METADATA.getString());
            connector.payloadDecoder(PayloadDecoder.DEFAULT);


            String username = "user";

            username = username + ":" + this.sessionId;

            ByteBuf byteBuf = AuthMetadataCodec.encodeSimpleMetadata(ByteBufAllocator.DEFAULT, username.toCharArray(), "pass".toCharArray());

//            ByteBuf tagging = ByteBufAllocator.DEFAULT.buffer();
//            tagging.writeBytes(this.sessionId.getBytes(StandardCharsets.UTF_8));
//            TaggingMetadata sessionTag = TaggingMetadataCodec.createTaggingMetadata(new CompositeMetadata.ExplicitMimeTimeEntry(tagging, "x-session-id"));

            CompositeByteBuf metadata = ByteBufAllocator.DEFAULT.compositeBuffer();

            CompositeMetadataCodec.encodeAndAddMetadata(metadata,
                    ByteBufAllocator.DEFAULT,
                    WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION,
                    byteBuf
            );

//            CompositeMetadataCodec.encodeAndAddMetadata(metadata,
//                    ByteBufAllocator.DEFAULT,
//                    WellKnownMimeType.TEXT_PLAIN,
//                    tagging
//            );

            connector.setupPayload(DefaultPayload.create(DefaultPayload.EMPTY_BUFFER, metadata.nioBuffer()));

            rSocket = connector.connect(TcpClientTransport.create(addressParts[0], addressPort)).block();

//            RoutingMetadata routingMetadata = TaggingMetadataCodec.createRoutingMetadata(
//                    ByteBufAllocator.DEFAULT, Collections.singletonList("session-mapping")
//            );
//
//
//            CompositeByteBuf sessionInfoMetadata = ByteBufAllocator.DEFAULT.compositeBuffer();
//            CompositeMetadataCodec.encodeAndAddMetadata(sessionInfoMetadata,
//                    ByteBufAllocator.DEFAULT,
//                    WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
//                    routingMetadata.getContent());
//
//
//            ByteBuf sessionData = ByteBufAllocator.DEFAULT.buffer();
//
//            sessionData.writeInt(this.sessionId.length());
//            sessionData.writeBytes(this.sessionId.getBytes(StandardCharsets.UTF_8));
//            sessionData.writeInt(this.processId);
//
//            rSocket.fireAndForget(DefaultPayload.create(sessionData, sessionInfoMetadata)).block();

            System.out.printf("Connected to: [%s] Session Id [%s] Process Id [%s]\n", serverAddress, this.sessionId, this.processId);


        }
    }

    /**
     * A copy constructor with a constraint.
     *
     * @param config
     */
    public WeaveConfig(WeaveConfig parent, LogLevel level) {
        this.weaveExec = parent.weaveExec;
        this.weaveMethodCall = parent.weaveMethodCall;
        this.weaveFieldAccess = parent.weaveFieldAccess;
        this.weaveArray = parent.weaveArray;
        this.weaveSynchronization = parent.weaveSynchronization;
        this.weaveLabel = parent.weaveLabel;
        this.weaveParameters = parent.weaveParameters;
        this.weaveLocalAccess = parent.weaveLocalAccess;
        this.weaveLineNumber = parent.weaveLineNumber;
        this.ignoreArrayInitializer = parent.ignoreArrayInitializer;
        this.weaveNone = parent.weaveNone;
        if (level == LogLevel.IgnoreArrayInitializer) {
            this.ignoreArrayInitializer = true;
        } else if (level == LogLevel.OnlyEntryExit) {
            this.weaveMethodCall = false;
            this.weaveFieldAccess = false;
            this.weaveArray = false;
            this.weaveSynchronization = false;
            this.weaveLabel = false;
            this.weaveParameters = false;
            this.weaveLocalAccess = false;
            this.weaveObject = false;
            this.weaveLineNumber = false;
        }
    }

    /**
     * @return true if the weaver is configured to record some events or
     * explicitly configured to record no events.
     */
    public boolean isValid() {
        return weaveNone || weaveExec || weaveMethodCall || weaveFieldAccess || weaveArray || weaveSynchronization || weaveParameters || weaveLocalAccess || weaveLabel || weaveLineNumber;
    }

    /**
     * @return true if the weaver should record method execution events
     * such as ENTRY and EXIT observed in the callee side.
     */
    public boolean recordExecution() {
        return weaveExec;
    }

    /**
     * @return true if the weaver should record synchronized block events
     */
    public boolean recordSynchronization() {
        return weaveSynchronization;
    }

    /**
     * @return true if the weaver should record field access events
     */
    public boolean recordFieldAccess() {
        return weaveFieldAccess;
    }

    /**
     * @return true if the weaver should record method execution events
     * such as CALL observed in the caller side.
     */
    public boolean recordMethodCall() {
        return weaveMethodCall;
    }

    /**
     * @return true if the weaver should record array manipulation events.
     */
    public boolean recordArrayInstructions() {
        return weaveArray;
    }

    /**
     * @return true if the weaver should record LABEL (control-flow) events.
     */
    public boolean recordLabel() {
        return weaveLabel;
    }

    /**
     * @return true if the weaver should record method parameters.
     */
    public boolean recordParameters() {
        return weaveParameters;
    }

    /**
     * @return true if the weaver should record local access events.
     */
    public boolean recordLocalAccess() {
        return weaveLocalAccess;
    }

    /**
     * @return true if the weaver should record line number events.
     */
    public boolean recordLineNumber() {
        return weaveLineNumber;
    }

    /**
     * @return true if the weaving should ignore array initializers
     * (due to the size of the target class file).
     */
    public boolean ignoreArrayInitializer() {
        return ignoreArrayInitializer;
    }

    /**
     * @return true if the weaver should record CATCH events.
     */
    public boolean recordCatch() {
        return recordMethodCall() ||
                recordFieldAccess() ||
                recordArrayInstructions() ||
                recordLabel() ||
                recordSynchronization();
    }

    /**
     * @return true if the weaver should record OBJECT events.
     */
    public boolean recordObject() {
        return weaveObject;
    }


    /**
     * Save the weaving configuration to a file.
     *
     * @param propertyFile
     */
    public void save(File propertyFile) {
        ArrayList<String> events = new ArrayList<String>();
        if (weaveExec) events.add(KEY_RECORD_EXEC);
        if (weaveMethodCall) events.add(KEY_RECORD_CALL);
        if (weaveFieldAccess) events.add(KEY_RECORD_FIELD);
        if (weaveArray) events.add(KEY_RECORD_ARRAY);
        if (weaveSynchronization) events.add(KEY_RECORD_SYNC);
        if (weaveLabel) events.add(KEY_RECORD_LABEL);
        if (weaveParameters) events.add(KEY_RECORD_PARAMETERS);
        if (weaveLocalAccess) events.add(KEY_RECORD_LOCAL);
        if (weaveObject) events.add(KEY_RECORD_OBJECT);
        if (weaveLineNumber) events.add(KEY_RECORD_LINE);
        if (weaveNone) events.add(KEY_RECORD_NONE);
        StringBuilder eventsString = new StringBuilder();
        for (int i = 0; i < events.size(); ++i) {
            if (i > 0) eventsString.append(KEY_RECORD_SEPARATOR);
            eventsString.append(events.get(i));
        }

        Properties prop = new Properties();
        prop.setProperty(KEY_RECORD, eventsString.toString());

        try {
            FileOutputStream out = new FileOutputStream(propertyFile);
            prop.store(out, "Generated: " + new Date().toString());
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public RSocket getRsocket() {
        return rSocket;
    }


}
