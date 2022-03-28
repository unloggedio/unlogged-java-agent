package com.videobug.agent.logging.io;

import com.videobug.agent.logging.IErrorLogger;
import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.util.*;
import com.videobug.agent.logging.util.*;
import io.rsocket.RSocket;

import java.io.File;
import java.io.IOException;

/**
 * This class is an implementation of IEventLogger that records
 * a sequence of runtime events in files.
 * This object creates three types of files:
 * 1. log-*.slg files recording a sequence of events,
 * 2. LOG$Types.txt recording a list of type IDs and their corresponding type names,
 * 3. ObjectIdMap recording a list of object IDs and their type IDs.
 * Using the second and third files, a user can know classes in an execution trace.
 */
public class EventStreamNetworkLogger implements IEventLogger {

    public static final String FILENAME_TYPEID = "LOG$Types.txt";

    public static final String LOG_PREFIX = "log-";
    public static final String LOG_SUFFIX = ".slg";
    private EventDataNetworkStream networkStream;
    private RSocket rSocket;

    private File outputDir;
    private IErrorLogger errorLogger;
    private EventDataStream stream;

    private TypeIdMap typeToId;
    private ObjectIdStream objectIdMap;

    /**
     * Create an instance of logging object.
     *
     * @param logger       specifies an object to record errors that occur in this class
     * @param outputDir    specifies a directory for output files.
     * @param recordString If this is set to true, the object also records contents of string objects.
     * @param processId
     */
    public EventStreamNetworkLogger(IErrorLogger logger, File outputDir, RSocket rSocket, boolean recordString, Integer processId) {
        try {
            this.outputDir = outputDir;
            this.errorLogger = logger;
            this.rSocket = rSocket;
            stream = new EventDataStream(new FileNameGenerator(outputDir, LOG_PREFIX, LOG_SUFFIX), errorLogger);
            networkStream = new EventDataNetworkStream(this.rSocket, errorLogger, processId);
            typeToId = new TypeIdMap();
            objectIdMap = new ObjectIdStream(outputDir, recordString, typeToId, this.rSocket, processId);
        } catch (IOException e) {
            errorLogger.log("We cannot record runtime information: " + e.getLocalizedMessage());
            errorLogger.log(e);
        }
    }

    /**
     * Close all file streams used by the object.
     */
    public void close() {
        stream.close();
        objectIdMap.close();
        typeToId.save(new File(outputDir, FILENAME_TYPEID));
    }

    /**
     * Record an event and an object.
     * The object is translated into an object ID.
     */
    public void recordEvent(int dataId, Object value) {
        long objectId = objectIdMap.getId(value);
        stream.write(dataId, objectId);
        networkStream.write(dataId, objectId);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, int value) {
        stream.write(dataId, value);
        networkStream.write(dataId, value);
    }

    /**
     * Record an event and an integer value.
     */
    public void recordEvent(int dataId, long value) {
        stream.write(dataId, value);
        networkStream.write(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, byte value) {
        stream.write(dataId, value);
        networkStream.write(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, short value) {
        stream.write(dataId, value);
        networkStream.write(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value.
     */
    public void recordEvent(int dataId, char value) {
        stream.write(dataId, value);
        networkStream.write(dataId, value);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value (true = 1, false = 0).
     */
    public void recordEvent(int dataId, boolean value) {
        int longValue = value ? 1 : 0;
        stream.write(dataId, longValue);
        networkStream.write(dataId, longValue);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value preserving the information.
     */
    public void recordEvent(int dataId, double value) {
        long longValue = Double.doubleToRawLongBits(value);
        stream.write(dataId, longValue);
        networkStream.write(dataId, longValue);
    }

    /**
     * Record an event and an integer value.
     * To simplify the file writing process, the value is translated into a long value preserving the information.
     */
    public void recordEvent(int dataId, float value) {
        int longValue = Float.floatToRawIntBits(value);
        stream.write(dataId, longValue);
        networkStream.write(dataId, longValue);
    }


    @Override
    public void recordWeaveInfo(byte[] byteArray) {

    }


}
