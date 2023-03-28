package com.videobug.agent.weaver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videobug.agent.command.AgentCommandExecutor;
import com.videobug.agent.command.AgentCommandRequest;
import com.videobug.agent.command.AgentCommandResponse;
import com.videobug.agent.command.AgentCommandServer;
import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.Logging;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import com.videobug.agent.logging.perthread.RawFileCollector;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.logging.util.NetworkClient;
import com.videobug.agent.util.ClassTypeUtil;
import fi.iki.elonen.NanoHTTPD;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.WatchService;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class is the main program of SELogger as a javaagent.
 */
public class RuntimeWeaver implements ClassFileTransformer, AgentCommandExecutor {

    public static final int AGENT_SERVER_PORT = 12100;
    private static boolean initialized = false;
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
    private Map<String, WatchService> watchedLocations = new HashMap<String, WatchService>();
    private ObjectMapper objectMapper = new ObjectMapper();


    /**
     * Process command line arguments and prepare an output directory
     *
     * @param args string arguments for weaver
     */
    public RuntimeWeaver(String args) {

        try {
            params = new RuntimeWeaverParameters(args);

            File outputDir = new File(params.getOutputDirname());
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            AgentCommandServer httpServer = new AgentCommandServer(AGENT_SERVER_PORT);
            httpServer.setAgentCommandExecutor(this);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            System.err.println("unlogged agent server started at port " + AGENT_SERVER_PORT);


            if (outputDir.isDirectory() && outputDir.canWrite()) {
                WeaveConfig config = new WeaveConfig(params);
                if (config.isValid()) {
                    weaver = new Weaver(outputDir, config);
                    weaver.setDumpEnabled(params.isDumpClassEnabled());
                    System.out.println("[videobug]" +
                            " session Id: [" + config.getSessionId() + "]" +
                            " on hostname [" + NetworkClient.getHostname() + "]");
                    weaver.log("Params: " + args);

                    switch (params.getMode()) {

                        case PerThread:

                            NetworkClient networkClient = new NetworkClient(params.getServerAddress(),
                                    config.getSessionId(), params.getAuthToken(), weaver);

                            FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");
                            RawFileCollector fileCollector =
                                    new RawFileCollector(params.getFilesPerIndex(), fileNameGenerator1, networkClient,
                                            weaver, outputDir);

                            FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
                            PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                                    = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, weaver, fileCollector);

                            logger = Logging.initialiseAggregatedLogger(weaver, perThreadBinaryFileAggregatedLogger,
                                    outputDir);
                            break;

                        case Testing:

                            NetworkClient networkClient1 =
                                    new NetworkClient(params.getServerAddress(),
                                            config.getSessionId(), params.getAuthToken(), weaver);

                            FileNameGenerator fileNameGenerator2 =
                                    new FileNameGenerator(outputDir, "index-", ".zip");
                            RawFileCollector fileCollector1 =
                                    new RawFileCollector(params.getFilesPerIndex(), fileNameGenerator2, networkClient1,
                                            weaver, outputDir);

                            FileNameGenerator fileNameGenerator3 =
                                    new FileNameGenerator(outputDir, "log-", ".selog");
                            PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger1
                                    = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator3, weaver,
                                    fileCollector1);

                            logger = Logging.initialiseDetailedAggregatedLogger(
                                    this.params.getIncludedNames().get(0), perThreadBinaryFileAggregatedLogger1,
                                    outputDir);
                            break;

                    }
                } else {
                    System.out.println("[videobug] no weaving option is specified.");
                    weaver = null;
                }
            } else {
                System.err.println("[videobug] ERROR: " + outputDir.getAbsolutePath() + " is not writable.");
                weaver = null;
            }
        } catch (Throwable thx) {
            System.err.println(
                    "[videobug] agent init failed, this session will not be recorded => " + thx.getMessage());
            thx.printStackTrace();
            if (thx.getCause() != null) {
                thx.getCause()
                        .printStackTrace();
            }
        }
    }

    /**
     * The entry point of the agent.
     * This method initializes the Weaver instance and setup a shutdown hook
     * for releasing resources on the termination of a target program.
     *
     * @param agentArgs comes from command line.
     * @param inst      is provided by the jvm
     */
    public static void premain(String agentArgs, Instrumentation inst) throws IOException {
        String agentVersion = RuntimeWeaver.class.getPackage()
                .getImplementationVersion();
        System.out.println("[videobug] Starting agent: [" + agentVersion + "] with arguments [" + agentArgs + "]");
        String processId = ManagementFactory.getRuntimeMXBean()
                .getName();
        long startTime = new Date().getTime();

        String lockFileName = ".videobug" + processId + ".lock";
        File lockFile = new File(lockFileName);
        if (lockFile.exists()) {
            System.out.println("[videobug] agent already loaded -> " + lockFileName + ". Delete the lock file and " +
                    "restart to start recording.");
            return;
        }
        lockFile.createNewFile();
        lockFile.deleteOnExit();

        final RuntimeWeaver runtimeWeaver = new RuntimeWeaver(agentArgs);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
//                        System.out.println("[videobug] shutting down");
                        runtimeWeaver.close();
                        System.out.println("[videobug] shutdown complete");
                    }
                }));

        if (runtimeWeaver.isValid()) {
            inst.addTransformer(runtimeWeaver);
        }
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
        logger.close();
        weaver.close();
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

                System.err.println(
                        "[" + new Date() + "] Weaving executed: " + className + " loaded from " + classLoadLocation);
                if (existingClass.containsKey(className)) {
                    Exception exception = new Exception();
                    exception.printStackTrace(System.err);
                }
                existingClass.put(className, classLoadLocation);

//                if (!watchedLocations.containsKey(classLoadLocation)) {
//                    Path loaderPath = FileSystems.getDefault().getPath(classLoadLocation);
//                    WatchService watcher = FileSystems.getDefault().newWatchService();
//                    loaderPath.register(watcher, ENTRY_MODIFY);
//                    watchedLocations.put(classLoadLocation, watcher);
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

    @Override
    public AgentCommandResponse executeCommand(AgentCommandRequest agentCommandRequest) throws Exception {
        System.err.println("AgentCommandRequest: " + agentCommandRequest);


        Object hibernateSessionFactory = logger.getObjectByClassName("org.hibernate.internal.SessionFactoryImpl");
        Object sessionInstance = null;
        if (hibernateSessionFactory != null) {
            System.err.println("Hibernate session factory: " + hibernateSessionFactory);
            Method openSessionMethod = hibernateSessionFactory.getClass().getMethod("openSession");
            sessionInstance = openSessionMethod.invoke(hibernateSessionFactory);
            System.err.println("Hibernate session opened: " + sessionInstance);
            Class<?> managedSessionContextClass = Class.forName("org.hibernate.context.internal.ManagedSessionContext");
            Method bindMethod = managedSessionContextClass.getMethod("bind", Class.forName("org.hibernate.Session"));
            bindMethod.invoke(null, sessionInstance);


            Method beginTransactionMethod = sessionInstance.getClass().getMethod("beginTransaction");
            beginTransactionMethod.invoke(sessionInstance);
        }
        try {


            Object objectByClass = logger.getObjectByClassName(agentCommandRequest.getClassName());
            if (objectByClass == null) {
                System.err.println("No object by classname: " + agentCommandRequest.getClassName());
                throw new Exception("No object by classname: " + agentCommandRequest.getClassName());
            }
            System.err.println("Object instance: " + objectByClass);

            Method methodToExecute = null;
            Class<?> objectClass = objectByClass.getClass();

            List<String> methodSignatureParts = ClassTypeUtil.splitMethodDesc(agentCommandRequest.getMethodSignature());
            String methodReturnType = methodSignatureParts.remove(methodSignatureParts.size() - 1);

            List<String> methodParameters = agentCommandRequest.getMethodParameters();
            System.err.println(
                    "Method parameters from received signature: " + methodSignatureParts + " => " + methodParameters);

            Class<?>[] methodParameterTypes = new Class[methodSignatureParts.size()];

            for (int i = 0; i < methodSignatureParts.size(); i++) {
                String methodSignaturePart = methodSignatureParts.get(i);
//                System.err.println("Method parameter [" + i + "] type: " + methodSignaturePart);
                methodParameterTypes[i] = ClassTypeUtil.getClassNameFromDescriptor(methodSignaturePart);
            }


            try {
                methodToExecute = objectClass.getMethod(agentCommandRequest.getMethodName(), methodParameterTypes);
            } catch (NoSuchMethodException noSuchMethodException) {
                System.err.println("method not found matching name [" + agentCommandRequest.getMethodName() + "]" +
                        " with parameters [" + methodSignatureParts + "]" +
                        " in class [" + agentCommandRequest.getClassName() + "]");
                System.err.println("NoSuchMethodException: " + noSuchMethodException.getMessage());
            }

//            if (methodToExecute == null) {
//                throw new IllegalArgumentException(
//                        "method not found matching name [" + agentCommandRequest.getMethodName() + "]" +
//                                " with parameters [" + methodSignatureParts + "]" +
//                                " in class [" + agentCommandRequest.getClassName() + "]");
//            }

            Method[] methods = objectClass.getMethods();
            for (Method method : methods) {
                if (method.getName().equals(agentCommandRequest.getMethodName())) {
                    methodToExecute = method;
                    break;
                }
            }

            if (methodToExecute == null) {
                System.err.println("Method not found: " + agentCommandRequest.getMethodName()
                        + ", methods were: " + Arrays.stream(methods).map(Method::getName)
                        .collect(Collectors.toList()));
                throw new NoSuchMethodException("method not found [" + agentCommandRequest.getMethodName()
                                + "] in class " + agentCommandRequest.getClassName());
            }

            Class<?>[] parameterTypesClass = methodToExecute.getParameterTypes();
            Object[] parameters = new Object[methodParameters.size()];

            for (int i = 0; i < methodParameters.size(); i++) {
                String methodParameter = methodParameters.get(i);
                Class<?> parameterType = parameterTypesClass[i];
                System.err.println("Make value of type [" + parameterType + "] from value: " + methodParameter);
                Object parameterObject = objectMapper.readValue(methodParameter, parameterType);
//                System.err.println(
//                        "Assign parameter [" + i + "] value type [" + parameterType + "] -> " + parameterObject);
                parameters[i] = parameterObject;
            }


            if (methodToExecute == null) {
                throw new Exception("Method [" + agentCommandRequest.getMethodName() + "]" +
                        " not found in class [" + agentCommandRequest.getClassName() + "]");
            }


            Object methodReturnValue = methodToExecute.invoke(objectByClass, parameters);
            AgentCommandResponse agentCommandResponse = new AgentCommandResponse();
            agentCommandResponse.setMethodReturnValue(methodReturnValue);
            return agentCommandResponse;
        } finally {
            if (sessionInstance != null) {

                Method getTransactionMethod = sessionInstance.getClass().getMethod("getTransaction");
                Object transactionInstance = getTransactionMethod.invoke(sessionInstance);
                System.err.println("Transaction to commit: " + transactionInstance);
                Method commitMethod = transactionInstance.getClass().getMethod("commit");
                commitMethod.invoke(transactionInstance);


                Method sessionCloseMethod = sessionInstance.getClass().getMethod("close");
                sessionCloseMethod.invoke(sessionInstance);
            }
        }


    }

    public enum Mode {Stream, Frequency, FixedSize, Discard, Network, PerThread, Testing}

}
