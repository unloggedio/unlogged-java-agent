package com.videobug.agent.weaver;

import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.logging.Logging;
import com.videobug.agent.logging.perthread.PerThreadBinaryFileAggregatedLogger;
import com.videobug.agent.logging.perthread.RawFileCollector;
import com.videobug.agent.logging.util.FileNameGenerator;
import com.videobug.agent.logging.util.NetworkClient;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * This class is the main program of SELogger as a javaagent.
 */
public class RuntimeWeaver implements ClassFileTransformer {

    /**
     * The weaver injects logging instructions into target classes.
     */
    private Weaver weaver;
    private RuntimeWeaverParameters params;
    /**
     * The logger receives method calls from injected instructions via selogger.logging.Logging class.
     */
    private IEventLogger logger;


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

            if (outputDir.isDirectory() && outputDir.canWrite()) {
                WeaveConfig config = new WeaveConfig(params);
                if (config.isValid()) {
                    weaver = new Weaver(outputDir, config);
                    weaver.setDumpEnabled(params.isDumpClassEnabled());
                    System.out.println("[videobug] session Id: [" + config.getSessionId() +
                            "] on hostname [" + NetworkClient.getHostname() + "]");
                    weaver.log("Params: " + args);

                    switch (params.getMode()) {

                        case PerThread:

                            NetworkClient networkClient = new NetworkClient(params.getServerAddress(),
                                    config.getSessionId(), params.getAuthToken(), weaver);

                            FileNameGenerator fileNameGenerator1 = new FileNameGenerator(outputDir, "index-", ".zip");
                            RawFileCollector fileCollector =
                                    new RawFileCollector(params.getFilesPerIndex(), fileNameGenerator1, networkClient, weaver);

                            outputDir.mkdirs();
                            FileNameGenerator fileNameGenerator = new FileNameGenerator(outputDir, "log-", ".selog");
                            PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger
                                    = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator, weaver, fileCollector);

                            logger = Logging.initialiseAggregatedLogger(weaver, perThreadBinaryFileAggregatedLogger, outputDir);
                            break;

                        case Testing:

                            NetworkClient networkClient1 =
                                    new NetworkClient(params.getServerAddress(),
                                            config.getSessionId(), params.getAuthToken(), weaver);

                            FileNameGenerator fileNameGenerator2 =
                                    new FileNameGenerator(outputDir, "index-", ".zip");
                            RawFileCollector fileCollector1 =
                                    new RawFileCollector(params.getFilesPerIndex(), fileNameGenerator2, networkClient1, weaver);

                            outputDir.mkdirs();
                            FileNameGenerator fileNameGenerator3 =
                                    new FileNameGenerator(outputDir, "log-", ".selog");
                            PerThreadBinaryFileAggregatedLogger perThreadBinaryFileAggregatedLogger1
                                    = new PerThreadBinaryFileAggregatedLogger(fileNameGenerator3, weaver, fileCollector1);

                            logger =
                                    Logging.initialiseDetailedAggregatedLogger(this.params.getIncludedNames().get(0),
                                            perThreadBinaryFileAggregatedLogger1, outputDir);
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
            System.err.println("[videobug] agent init failed, this session will not be recorded => " + thx.getMessage());
            thx.printStackTrace();
            if (thx.getCause() != null) {
                thx.getCause().printStackTrace();
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
        String agentVersion = RuntimeWeaver.class.getPackage().getImplementationVersion();
        System.out.println("[videobug] starting Agent: [" + agentVersion + "] with arguments [" + agentArgs + "]");

        final RuntimeWeaver runtimeWeaver = new RuntimeWeaver(agentArgs);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[videobug] shutting down");
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
        )
            return true;
        ArrayList<String> includedNames = params.getIncludedNames();
        for (String ex : params.getExcludedNames()) {
            if (className.startsWith(ex)) {
                return true;
            }
        }
        if (includedNames.size() > 0) {
            for (String ex : includedNames) {
                if (className.startsWith(ex) ||
                        "*".equals(ex) ||
                        Pattern.compile(ex).matcher(className).matches()) {
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

//        System.out.printf("Load class: [%s]\n", className);
        if (isExcludedFromLogging(className)) {
//            weaver.log("Excluded by name filter: " + className);
            return null;
        }

        if (protectionDomain != null) {
            CodeSource s = protectionDomain.getCodeSource();
            String l;
            if (s != null) {
                l = s.getLocation().toExternalForm();
            } else {
                l = "(Unknown Source)";
            }

            if (isExcludedLocation(l)) {
                weaver.log("Excluded by location filter: " + className + " loaded from " + l);
                return null;
            }

            if (isSecurityManagerClass(className, loader) && !params.isWeaveSecurityManagerClassEnabled()) {
                weaver.log("Excluded security manager subclass: " + className);
                return null;
            }

            weaver.log("Weaving executed: " + className + " loaded from " + l);
            byte[] buffer = weaver.weave(l, className, classfileBuffer, loader);

            return buffer;
        } else {
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

    public enum Mode {Stream, Frequency, FixedSize, Discard, Network, PerThread, Testing}

}
