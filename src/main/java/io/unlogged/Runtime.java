package io.unlogged;

import com.insidious.common.weaver.ClassInfo;
import fi.iki.elonen.NanoHTTPD;
import io.unlogged.command.AgentCommandExecutorImpl;
import io.unlogged.command.AgentCommandServer;
import io.unlogged.command.ServerMetadata;
import io.unlogged.logging.IErrorLogger;
import io.unlogged.logging.IEventLogger;
import io.unlogged.logging.Logging;
import io.unlogged.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import io.unlogged.logging.perthread.RawFileCollector;
import io.unlogged.logging.util.FileNameGenerator;
import io.unlogged.logging.util.NetworkClient;
import io.unlogged.util.StreamUtil;
import io.unlogged.weaver.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * This class is the main program of SELogger as a javaagent.
 */
public class Runtime {

    private static Runtime instance;
    private static List<Pair<String, String>> pendingClassRegistrations = new ArrayList<>();
    private final ScheduledExecutorService probeReaderExecutor = Executors.newSingleThreadScheduledExecutor();
    private WeaveParameters weaveParameters;
    private File outputDir;
    private WeaveConfig config;
    private AgentCommandServer httpServer;
    private IErrorLogger errorLogger;
    /**
     * The logger receives method calls from injected instructions via selogger.logging.Logging class.
     */
    private IEventLogger logger = Logging.initialiseDiscardLogger();
    private long lastProbesLoadTime;
    private ClassLoader classLoader;

    /**
     * Process command line arguments and prepare an output directory
     *
     * @param args string arguments for weaver
     */
    private Runtime(String args) {
//        System.err.println("UnloggedInit1" );

        try {
            weaveParameters = new WeaveParameters(args);
            Mode mode = (Mode) weaveParameters.getClass().getMethod("getMode").invoke(weaveParameters);


            ServerMetadata serverMetadata =
                    new ServerMetadata(weaveParameters.getIncludedNames().toString(), Constants.AGENT_VERSION,
                            0);

            httpServer = new AgentCommandServer(0, serverMetadata);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);


            outputDir = new File(weaveParameters.getOutputDirname());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            if (!outputDir.isDirectory() || !outputDir.canWrite()) {
                System.err.println("[unlogged] ERROR: " + outputDir.getAbsolutePath() + " is not writable.");
                return;
            }

            config = new WeaveConfig(weaveParameters);

            if (!config.isValid()) {
                System.out.println("[unlogged] no weaving option is specified.");
                return;
            }

            errorLogger = new SimpleFileLogger(outputDir);

            errorLogger.log("Java version: " + System.getProperty("java.version"));
            errorLogger.log("Agent version: " + Constants.AGENT_VERSION);
            errorLogger.log("Params: " + args);

            serverMetadata.setAgentServerUrl("http://localhost:" + httpServer.getListeningPort());
            serverMetadata.setAgentServerPort(httpServer.getListeningPort());
            errorLogger.log(serverMetadata.toString());

            System.out.println("[unlogged]" +
                    " session Id: [" + config.getSessionId() + "]" +
                    " on hostname [" + NetworkClient.getHostname() + "]: " + mode);

            switch (mode) {


                case DISCARD:
                    logger = Logging.initialiseDiscardLogger();
                    break;

                case PER_THREAD:

                    NetworkClient networkClient = new NetworkClient(weaveParameters.getServerAddress(),
                            config.getSessionId(), weaveParameters.getAuthToken(), errorLogger);

                    FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");
                    RawFileCollector fileCollector =
                            new RawFileCollector(weaveParameters.getFilesPerIndex(), fileNameGenerator1,
                                    networkClient, errorLogger, outputDir);

                    FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
                    PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                            = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, errorLogger, fileCollector);

                    logger = Logging.initialiseAggregatedLogger(perThreadBinaryFileAggregatedLogger, outputDir);
                    break;

                case TESTING:

                    NetworkClient networkClient1 =
                            new NetworkClient(weaveParameters.getServerAddress(),
                                    config.getSessionId(), weaveParameters.getAuthToken(), errorLogger);

                    FileNameGenerator archiveFileNameGenerator =
                            new FileNameGenerator(outputDir, "index-", ".zip");

                    RawFileCollector fileCollector1 =
                            new RawFileCollector(weaveParameters.getFilesPerIndex(), archiveFileNameGenerator,
                                    networkClient1, errorLogger, outputDir);

                    FileNameGenerator logFileNameGenerator =
                            new FileNameGenerator(outputDir, "log-", ".selog");

                    PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger1
                            = new PerThreadBinaryFileAggregatedLogger(logFileNameGenerator, errorLogger,
                            fileCollector1);

                    logger = Logging.initialiseDetailedAggregatedLogger(perThreadBinaryFileAggregatedLogger1,
                            outputDir);

                    break;

            }


            httpServer.setAgentCommandExecutor(new AgentCommandExecutorImpl(logger.getObjectMapper(), logger));

            System.out.println("[unlogged] agent server started at port " + httpServer.getListeningPort());

            java.lang.Runtime.getRuntime()
                    .addShutdownHook(new Thread(this::close));


        } catch (Throwable thx) {
            logger = Logging.initialiseDiscardLogger();
            thx.printStackTrace();
            System.err.println(
                    "[unlogged] agent init failed, this session will not be recorded => " + thx.getMessage());
        }
    }

    public static Runtime getInstance(String args) {
        if (instance != null) {
            return instance;
        }
        synchronized (Runtime.class) {
            if (instance != null) {
                return instance;
            }

            try {
                StackTraceElement callerClassAndMethodStack = new Exception().getStackTrace()[1];
                Class<?> callerClass = Class.forName(callerClassAndMethodStack.getClassName());
//                for (Method method : callerClass.getMethods()) {
//                    if (method.getAnnotation(Unlogged.class) != null) {
//                        // caller method
//                        Unlogged annotationData = method.getAnnotation(Unlogged.class);
//                        if (!annotationData.enable()) {
//                            return null;
//                        }
//                        break;
//                    }
//                }

            } catch (ClassNotFoundException e) {
                // should never happen
                // disable if happened
                return null;
            }
            instance = new Runtime(args);
            for (Pair<String, String> pendingClassRegistration : pendingClassRegistrations) {
                registerClass(pendingClassRegistration.getFirst(), pendingClassRegistration.getSecond());
            }

        }
        return instance;
    }

    // this method is called by all classes which were probed during compilation time
    public static void registerClass(String classInfoBytes, String probesToRecordBase64) {
//        System.out.println(
//                "New class registration [" + classInfoBytes.getBytes().length + "][" + probesToRecordBase64.getBytes().length + "]");
        if (instance != null) {

            byte[] decodedClassWeaveInfo = new byte[0];
            List<Integer> probesToRecord = null;
            try {
                decodedClassWeaveInfo = ByteTools.decompressBase64String(classInfoBytes);
                byte[] decodedProbesToRecord = ByteTools.decompressBase64String(probesToRecordBase64);
                probesToRecord = bytesToIntList(decodedProbesToRecord);
            } catch (IOException e) {
                // class registration fails
                // no recoding for this class
                System.out.println("Registration for class failed: " + e.getMessage());
                return;
            }
            ClassInfo classInfo = new ClassInfo();

            try {
                ByteArrayInputStream in = new ByteArrayInputStream(decodedClassWeaveInfo);
                classInfo.readFromDataStream(in);
            } catch (IOException e) {
                return;
            }
//            System.out.println("Register class ["+ classInfo.getClassId() +"][" + classInfo.getClassName() + "] => " + probesToRecord.size() +
//                    " probes to record");
            instance.logger.recordWeaveInfo(decodedClassWeaveInfo, classInfo, probesToRecord);
        } else {
//            System.out.println("Adding class to pending registrations");
            pendingClassRegistrations.add(new Pair<>(classInfoBytes, probesToRecordBase64));
        }
    }


    public static List<Integer> bytesToIntList(byte[] probeToRecordBytes) throws IOException {
        List<Integer> probesToRecord = new ArrayList<>();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(probeToRecordBytes));
        try {
            while (true) {
                int probeId = dis.readInt();
                probesToRecord.add(probeId);
            }
        } catch (EOFException e) {

        }
        return probesToRecord;
    }

    private List<Integer> probeFileToIdList(File file) throws IOException {
        InputStream probesFile = this.getClass().getClassLoader().getResourceAsStream(file.getName());
        return probeFileStreamToIdList(probesFile);
    }

    private List<Integer> probeFileStreamToIdList(InputStream probesFile) throws IOException {
        if (probesFile == null) {
            return new ArrayList<>();
        }
        byte[] probeToRecordBytes = StreamUtil.streamToBytes(probesFile);
        return bytesToIntList(probeToRecordBytes);
    }

    /**
     * Close data streams if necessary
     */
    public void close() {
        if (logger != null) {
            logger.close();
        }
        if (httpServer != null) {
            httpServer.stop();

        }
        if (errorLogger != null) {
            errorLogger.close();
        }
        System.out.println("[unlogged] shutdown complete");

    }

    public IEventLogger getLogger() {
        return logger;
    }

    public File getOutputDir() {
        return outputDir;
    }

    public WeaveConfig getConfig() {
        return config;
    }

    public IErrorLogger getErrorLogger() {
        return errorLogger;
    }

    public WeaveParameters getWeaveParameters() {
        return weaveParameters;
    }

    public void setTargetClassLoader(ClassLoader loader) {
        classLoader = loader;
        httpServer.setClassLoader(classLoader);
    }

    public enum Mode {STREAM, FREQUENCY, FIXED_SIZE, DISCARD, NETWORK, PER_THREAD, TESTING}

}
