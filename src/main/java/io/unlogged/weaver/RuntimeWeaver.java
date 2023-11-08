package io.unlogged.weaver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insidious.common.weaver.ClassInfo;
import fi.iki.elonen.NanoHTTPD;
import io.unlogged.Constants;
import io.unlogged.command.AgentCommandExecutorImpl;
import io.unlogged.command.AgentCommandServer;
import io.unlogged.command.ServerMetadata;
import io.unlogged.logging.IEventLogger;
import io.unlogged.logging.Logging;
import io.unlogged.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import io.unlogged.logging.perthread.RawFileCollector;
import io.unlogged.logging.util.FileNameGenerator;
import io.unlogged.logging.util.NetworkClient;
import org.objectweb.asm.ClassReader;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * This class is the main program of SELogger as a javaagent.
 */
public class RuntimeWeaver implements ClassFileTransformer {

    public static final int AGENT_SERVER_PORT = 12100;
    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static RuntimeWeaver instance;
    private final Instrumentation instrumentation;
    /**
     * The weaver injects logging instructions into target classes.
     */
    private Weaver weaver;
    private RuntimeWeaverParameters params;
    /**
     * The logger receives method calls from injected instructions via selogger.logging.Logging class.
     */
    private IEventLogger logger;
    private Map<String, String> existingClass = new HashMap<String, String>();
    private ObjectMapper objectMapper;

    /**
     * Process command line arguments and prepare an output directory
     *
     * @param args            string arguments for weaver
     * @param instrumentation object provided to the java agent for hooking up the class loader
     */
    public RuntimeWeaver(String args, Instrumentation instrumentation) {

        this.instrumentation = instrumentation;
        RuntimeWeaver.instance = this;
        try {
            params = new RuntimeWeaverParameters(args);

            File outputDir = new File(params.getOutputDirname());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            ServerMetadata serverMetadata = new ServerMetadata(params.getIncludedNames().toString(),
                    Constants.AGENT_VERSION);
            AgentCommandServer httpServer = new AgentCommandServer(AGENT_SERVER_PORT, serverMetadata);
//            System.out.println("[unlogged] agent server started at port " + AGENT_SERVER_PORT);
            SimpleFileLogger errorLogger = new SimpleFileLogger(outputDir);

            errorLogger.log("Java version: " + System.getProperty("java.version"));
            errorLogger.log("Agent version: " + Constants.AGENT_VERSION);
            errorLogger.log("Params: " + args);


            if (outputDir.isDirectory() && outputDir.canWrite()) {
                WeaveConfig config = new WeaveConfig(params);
                if (config.isValid()) {
                    weaver = new Weaver(outputDir, config);
                    weaver.setDumpEnabled(params.isDumpClassEnabled());
                    System.out.println("[unlogged]" +
                            " session Id: [" + config.getSessionId() + "]" +
                            " on hostname [" + NetworkClient.getHostname() + "]");
                    weaver.log("Params: " + args);

                    switch (params.getMode()) {

                        case PER_THREAD:


                            NetworkClient networkClient = new NetworkClient(params.getServerAddress(),
                                    config.getSessionId(), params.getAuthToken(), errorLogger);

                            FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");
                            RawFileCollector fileCollector =
                                    new RawFileCollector(params.getFilesPerIndex(), fileNameGenerator1,
                                            networkClient, errorLogger, outputDir);

                            FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
                            PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                                    = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, errorLogger,
                                    fileCollector);

                            logger = Logging.initialiseAggregatedLogger(perThreadBinaryFileAggregatedLogger, outputDir);

                            break;

                        case TESTING:

                            NetworkClient networkClient1 =
                                    new NetworkClient(params.getServerAddress(),
                                            config.getSessionId(), params.getAuthToken(), errorLogger);

                            FileNameGenerator archiveFileNameGenerator =
                                    new FileNameGenerator(outputDir, "index-", ".zip");

                            RawFileCollector fileCollector1 =
                                    new RawFileCollector(params.getFilesPerIndex(), archiveFileNameGenerator,
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


                    objectMapper = logger.getObjectMapper();
                    httpServer.setAgentCommandExecutor(new AgentCommandExecutorImpl(logger.getObjectMapper(), logger));
                    httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

                } else {
                    System.out.println("[unlogged] no weaving option is specified.");
                    weaver = null;
                }
            } else {
                System.err.println("[unlogged] ERROR: " + outputDir.getAbsolutePath() + " is not writable.");
                weaver = null;
            }
        } catch (Throwable thx) {
            System.err.println(
                    "[unlogged] agent init failed, this session will not be recorded => " + thx.getMessage());
            thx.printStackTrace();
            if (thx.getCause() != null) {
                thx.getCause().printStackTrace();
            }
        }
    }

    public static RuntimeWeaver getInstance(String args) {
        if (instance != null) {
            return instance;
        }
        return instance;
    }

    /**
     * The entry point of the agent.
     * This method initializes the Weaver instance and setup a shutdown hook
     * for releasing resources on the termination of a target program.
     *
     * @param agentArgs       comes from command line.
     * @param instrumentation is provided by the jvm
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println(
                "[unlogged] Starting agent: [" + Constants.AGENT_VERSION + "] with arguments [" + agentArgs + "]");
//        String processId = ManagementFactory.getRuntimeMXBean().getName();
//        long startTime = new Date().getTime();
        synchronized (initialized) {
            if (initialized.get()) {
                return;
            }
            initialized.set(true);
        }


        final RuntimeWeaver runtimeWeaver = new RuntimeWeaver(agentArgs, instrumentation);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    runtimeWeaver.close();
                    System.out.println("[unlogged] shutdown complete");
                }));

        if (runtimeWeaver.isValid()) {
            instrumentation.addTransformer(runtimeWeaver);
        }
    }

    private static void closeHibernateSessionIfPossible(Object sessionInstance) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        if (sessionInstance != null) {

            Method getTransactionMethod = sessionInstance.getClass().getMethod("getTransaction");
            Object transactionInstance = getTransactionMethod.invoke(sessionInstance);
//            System.err.println("Transaction to commit: " + transactionInstance);
            Method rollbackMethod = transactionInstance.getClass().getMethod("rollback");
            rollbackMethod.invoke(transactionInstance);


            Method sessionCloseMethod = sessionInstance.getClass().getMethod("close");
            sessionCloseMethod.invoke(sessionInstance);
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

    public static void registerClass(String classInfoBytes, String probesToRecordBase64) {
//        System.out.println(
//                "New class registration [" + classInfoBytes.getBytes().length + "][" + probesToRecordBase64.getBytes().length + "]");

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
    }

    public static void registerClassRaw(byte[] byteArray, ClassInfo classIdEntry, List<Integer> probeIdsToRecord) {
        instance.logger.recordWeaveInfo(byteArray, classIdEntry, probeIdsToRecord);
    }

    /**
     * @return true if the logging is executable
     */
    public boolean isValid() {
        return weaver != null && logger != null;
    }

    /**
     * Close data streams if necessary
     */
    public void close() {
        if (logger != null) {
            logger.close();
        }
        if (weaver != null) {
            weaver.close();
        }
    }

    /**
     * This method checks whether a given class is a logging target or not.
     *
     * @param className specifies a class.  A package separator is "/".
     * @return true if it is excluded from logging.
     */
    public boolean isExcludedFromLogging(String className) {
        if (
                className.startsWith("com/videobug/agent/")
                        && !className.startsWith("com/videobug/agent/testdata/")
        ) {
            return true;
        }
        ArrayList<String> includedNames = params.getIncludedNames();
        for (String ex : params.getExcludedNames()) {
            if (className.startsWith(ex)) {
                return true;
            }
        }
        if (includedNames.size() > 0) {
            for (String ex : includedNames) {
                if (className.startsWith(ex) || "*".equals(ex) || Pattern.compile(ex).matcher(className).matches()) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * This method checks whether a given class is a logging target or not.
     *
     * @param location is a loaded location (e.g. JAR or file path).
     * @return true if it is excluded from logging.
     */
    public boolean isExcludedLocation(String location) {
        for (String ex : params.getExcludedLocations()) {
            if (location.contains(ex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * This method is called from JVM when loading a class.
     * This agent injects logging instructions here.
     */
    @Override
    public synchronized byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                         ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

        if (className.startsWith("sun/")) {
            return null;
        }
        if (className.startsWith("jdk/")) {
            return null;
        }
        if (className.startsWith("java/")) {
            return null;
        }
        if (className.startsWith("javax/")) {
            return null;
        }
        if (className.startsWith("com/fasterxml")) {
            return null;
        }
        if (className.startsWith("io/micrometer")) {
            return null;
        }
        if (className.startsWith("org/wildfly/")) {
            return null;
        }
        if (className.startsWith("org/xnio/")) {
            return null;
        }
        if (className.startsWith("org/springframework/")) {
            return null;
        }
        if (className.startsWith("com/google/")) {
            return null;
        }
        if (className.startsWith("io/undertow/")) {
            return null;
        }
//        System.err.println("transform class: " + className);

        try {

            if (isExcludedFromLogging(className)) {
//            weaver.log("Excluded by name filter: " + className);
                return null;
            }

            if (protectionDomain != null) {
                CodeSource codeSource = protectionDomain.getCodeSource();
                String classLoadLocation;
                if (codeSource != null) {
                    classLoadLocation = codeSource.getLocation().toExternalForm();
                } else {
                    classLoadLocation = "(Unknown Source)";
                }

                if (isExcludedLocation(classLoadLocation)) {
                    weaver.log("Excluded by location filter: " + className + " loaded from " + classLoadLocation);
                    return null;
                }

                if (isSecurityManagerClass(className, loader) && !params.isWeaveSecurityManagerClassEnabled()) {
                    weaver.log("Excluded security manager subclass: " + className);
                    return null;
                }

//                System.err.println(
//                        "[" + new Date() + "] Weaving executed: " + className + " loaded from " + classLoadLocation);
                if (existingClass.containsKey(className)) {
                    System.err.println("Class [" + className + "] was hot-reloaded at " + new Date());
                }
                existingClass.put(className, classLoadLocation);


//                if (!watchedLocations.containsKey(classLoadLocation)) {
//                    Path loaderPath = FileSystems.getDefault().getPath(classLoadLocation.split("file:")[1]);
//
//
//                    System.err.println(
//                            "[" + this + "] Watching path: " + classLoadLocation + " => " + loaderPath.toFile()
//                                    .exists());
//                    WatchService watcher = FileSystems.getDefault().newWatchService();
//                    watchedLocations.put(classLoadLocation, watcher);
//                    registerAll(loaderPath, watcher);
//
//                    Runnable watcherRunnable = () -> {
//                        while (true) {
//                            try {
//                                WatchKey changeEvent = watcher.take();
//                                Path directory = (Path) changeEvent.watchable();
//                                for (WatchEvent<?> pollEvent : changeEvent.pollEvents()) {
//                                    if (pollEvent.kind() == ENTRY_DELETE) {
//                                        continue;
//                                    }
//                                    WatchEvent<Path> pathWatchEvent = (WatchEvent<Path>) pollEvent;
//                                    Path context = pathWatchEvent.context();
//                                    Path resolvedModifiedPath = directory.resolve(context);
//                                    if (!resolvedModifiedPath.toFile().isFile()) {
//                                        continue;
//                                    }
//                                    String pathString = resolvedModifiedPath.toString();
//                                    String nameForClassRedefinition = pathString.substring(
//                                            pathString.indexOf("classes/") + "classes/".length()
//                                            , pathString.indexOf(".class"));
//                                    System.err.println(
//                                            "WatchEvent: " + resolvedModifiedPath + " => " + pathWatchEvent.kind() +
//                                                    " => " + nameForClassRedefinition + " in " + directory.toAbsolutePath());
//                                    try (FileInputStream stream = new FileInputStream(resolvedModifiedPath.toFile())) {
//
//                                        byte[] newFileBytes = StreamUtil.streamToBytes(stream);
//                                        String classQualifiedName = nameForClassRedefinition.replace('/', '.');
//                                        Class<?> existingClass = Class.forName(classQualifiedName);
//                                        System.err.println("Existing class: " + existingClass);
//                                        ClassDefinition classDefinition = new ClassDefinition(existingClass, newFileBytes);
//                                        instrumentation.redefineClasses(classDefinition);
//                                    } catch (Exception e) {
//                                        e.printStackTrace();
//                                        System.err.println("Failed to redefine: " + e.getMessage());
//                                    }
//                                }
//                                changeEvent.reset();
//
//                            } catch (Exception e) {
//                                throw new RuntimeException(e);
//                            }
//                        }
//                    };
//                    threadPoolExecutor.submit(watcherRunnable);
//                }

                byte[] buffer = weaver.weave(classLoadLocation, className, classfileBuffer, loader);

                return buffer;
            } else {
                return null;
            }

        } catch (Throwable e) {
//            System.err.printf("[unlogged] Failed to instrument class: [%s]\n", className);
            return null;
        }
    }

    /**
     * Register the given directory and all its sub-directories with the WatchService.
     */
    private void registerAll(final Path start, WatchService watcher) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                System.err.println("Watching path: " + dir.toString());
                dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }

        });

    }

    /**
     * Check whether a given class is inherited from java.lang.SecurityManager or not.
     *
     * @param className specifies a class name.
     * @param loader    specifies a class loader.
     * @return true if the class is a subclass of SecurityManaer.
     */
    private boolean isSecurityManagerClass(String className, ClassLoader loader) {
        while (className != null) {
            if (className.equals("java/lang/SecurityManager")) {
                return true;
            } else if (className.equals("java/lang/Object")) {
                return false;
            }
            className = getSuperClass(className, loader);
        }
        return false;
    }

    /**
     * Get a super class name of a given class
     *
     * @param className specifies a class name
     * @param loader    specifies a class loader to load class information
     * @return the super class name.
     * Null is returnd if this method fails to load the class information
     */
    private String getSuperClass(String className, ClassLoader loader) {
        while (loader != null) {
            InputStream is = loader.getResourceAsStream(className + ".class");
            if (is != null) {
                try {
                    ClassReader r = new ClassReader(is);
                    is.close();
                    return r.getSuperName();
                } catch (IOException e) {
                    try {
                        is.close();
                    } catch (IOException e2) {
                    }
                }
                return null;
            }

            loader = loader.getParent();
        }
        return null;
    }

    public enum Mode {STREAM, FREQUENCY, FIXED_SIZE, DISCARD, NETWORK, PER_THREAD, TESTING}

}
