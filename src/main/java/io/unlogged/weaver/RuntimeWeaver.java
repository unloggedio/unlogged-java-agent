package io.unlogged.weaver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insidious.common.weaver.ClassInfo;
import io.unlogged.Constants;
import io.unlogged.Runtime;
import io.unlogged.command.AgentCommand;
import io.unlogged.command.AgentCommandRequest;
import io.unlogged.logging.IEventLogger;
import org.objectweb.asm.*;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
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
    private final Runtime runtime;
    /**
     * The weaver injects logging instructions into target classes.
     */
    private Weaver weaver;
    private WeaveParameters params;
    /**
     * The logger receives method calls from injected instructions via selogger.logging.Logging class.
     */
    private IEventLogger logger;
    private Map<String, String> existingClass = new HashMap<String, String>();
    private ObjectMapper objectMapper;
    private DataInfoProvider dataInfoProvider = new DataInfoProvider(0, 0, 0);

    /**
     * Process command line arguments and prepare an output directory
     *
     * @param args            string arguments for weaver
     * @param instrumentation object provided to the java agent for hooking up the class loader
     */
    public RuntimeWeaver(String args, Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        runtime = Runtime.getInstance(args);
        logger = runtime.getLogger();
        params = runtime.getWeaveParameters();
        weaver = new Weaver(runtime.getConfig(), new DataInfoProvider(0, 0, 0));
        RuntimeWeaver.instance = this;
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
//        String processId = ManagementFactory.getRuntimeMXBean().getName();
//        long startTime = new Date().getTime();

        synchronized (initialized) {
            if (initialized.get()) {
                return;
            }
            initialized.set(true);
        }
        System.out.println(
                "[unlogged] Starting agent: [" + Constants.AGENT_VERSION + "] with arguments [" + agentArgs + "]");


        final RuntimeWeaver runtimeWeaver = new RuntimeWeaver(agentArgs, instrumentation);
//        Runtime.getInstance("")
//                .addShutdownHook(new Thread(() -> {
//                    runtimeWeaver.close();
//                    System.out.println("[unlogged] shutdown complete");
//                }));

        if (runtimeWeaver.isValid()) {
            instrumentation.addTransformer(runtimeWeaver);
        } else {
            System.err.println("@runtimeWeaver.isValid()");
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
//            weaver.close();
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

    public byte[] applyTransformations(String classLoadLocation, byte[] original, String fileName,
                                       DataInfoProvider dataInfoProvider, ClassLoader classLoader) {

        ClassFileMetaData classFileMetadata = new ClassFileMetaData(original);
        InstrumentedClass instrumentedClassBytes;
        final String className = classFileMetadata.getClassName();
        for (String anInterface : classFileMetadata.getInterfaces()) {
//            System.err.println("Class [" + className + "] implements [" + anInterface + "]");
            if (anInterface.startsWith("reactor/core/CoreSubscriber")) {
                return original;
            }
            if (anInterface.startsWith("java/lang/annotation/Annotation")) {
                return original;
            }
            if (anInterface.startsWith("java/io/Serializable")) {
                return original;
            }
        }


        ByteArrayOutputStream probesToRecordOutputStream = new ByteArrayOutputStream();
        try {

            dataInfoProvider.setProbeOutputStream(probesToRecordOutputStream);
            weaver.setDataInfoProvider(dataInfoProvider);
            instrumentedClassBytes = weaver.weave(classLoadLocation, fileName, original, classLoader);
            dataInfoProvider.flushIdInformation();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] probesToRecordBytes = probesToRecordOutputStream.toByteArray();
        byte[] classWeaveInfo = instrumentedClassBytes.getClassWeaveInfo();
        if (classWeaveInfo.length < 5) {
            return original;
        }

//        ClassInfo classInfo = new ClassInfo();
//        classInfo.readFromDataStream(new ByteArrayInputStream(classWeaveInfo));

        final String probeDataCompressedBase64 = ByteTools.compressBase64String(probesToRecordBytes);
        final String compressedClassWeaveInfo = ByteTools.compressBase64String(classWeaveInfo);

//        List<Integer> probeIdsAgain = Runtime.bytesToIntList(
//                ByteTools.decompressBase64String(probeDataCompressedBase64));
//        System.out.println("Probes to record: " + probeDataCompressedBase64 + " === " + compressedClassWeaveInfo);


//        if (instrumentedClassBytes.classWeaveInfo.length == 0) {
//        return instrumentedClassBytes.getBytes();
//        }


//        final AtomicBoolean changesMade = new AtomicBoolean();
//        if (fileName.contains("$")) {
//            classWeaveBytesToBeWritten.write(classWeaveInfo);
//            return instrumentedClassBytes.getBytes();
//        }

        AgentCommandRequest agentCommandRequest = new AgentCommandRequest();
        List<String> parameters = Arrays.asList(compressedClassWeaveInfo, probeDataCompressedBase64);
        agentCommandRequest.setMethodParameters(parameters);
        agentCommandRequest.setCommand(AgentCommand.REGISTER_CLASS);
//        RequestBody body = RequestBody.create(objectMapper.writeValueAsString(agentCommandRequest), JSON);
//        Request request = new Request.Builder()
//                .url(agentUrl + "/command")
//                .post(body)
//                .build();
//
//        try (Response response = client.newCall(request).execute()) {
//            String responseBody = response.body().string();
//            AgentCommandResponse agentCommandResponse = objectMapper.readValue(responseBody,
//                    AgentCommandResponse.class);
//            // successfully posted to the running server
//            // hmm
//            if (agentCommandResponse.getResponseType() == ResponseType.NORMAL) {
////                System.err.println("Posted class weave info to running process directly yay");
////                diagnostics.addError("Posted class weave info to running process directly yay");
////                return instrumentedClassBytes.getBytes();
//            }
//        } catch (Throwable e) {
//            // server isnt up
//            // process isnt running
//        }


        // Create a ClassReader to read the original class bytes
        ClassReader reader = new ClassReader(instrumentedClassBytes.getBytes());

        // Create a ClassWriter to write the modified class bytes
        ClassWriter writer = new FixedClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        // Create a ClassVisitor to visit and modify the class
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, writer) {
            private boolean hasStaticInitializer = false;

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                // Check if this is the static initializer (clinit)
                if (name.equals("<clinit>")) {
                    hasStaticInitializer = true;
//                    System.out.println("Modify existing static method in [" + className + "]");
                    // Create a method visitor to add code to the static initializer
                    MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);

                    // Create a MethodVisitor to visit and modify the method
                    return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                        @Override
                        public void visitCode() {
                            addClassWeaveInfo(mv, compressedClassWeaveInfo, probeDataCompressedBase64);
                            mv.visitMaxs(3, 0);
                            super.visitCode();
                        }

                    };

                }

                return super.visitMethod(access, name, desc, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                // If the class doesn't have a static initializer, create one
                if (!hasStaticInitializer) {
//                    System.out.println("Adding new static method in [" + className + "]");

                    MethodVisitor methodVisitor = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                    methodVisitor.visitCode();
                    addClassWeaveInfo(methodVisitor, compressedClassWeaveInfo, probeDataCompressedBase64);
                    methodVisitor.visitMaxs(3, 0);
                    methodVisitor.visitInsn(Opcodes.RETURN);
                    methodVisitor.visitEnd();

                }

                super.visitEnd();
            }
        };

        // Start the class modification process
        reader.accept(cv, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
////        return original;
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
            runtime.setTargetClassLoader(loader);

            if (isExcludedFromLogging(className)) {
//                weaver.log("Excluded by name filter: " + className);
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
//                    weaver.log("Excluded by location filter: " + className + " loaded from " + classLoadLocation);
                    return null;
                }

                if (isSecurityManagerClass(className, loader) && !params.isWeaveSecurityManagerClassEnabled()) {
//                    weaver.log("Excluded security manager subclass: " + className);
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

//                System.err.println("Class [" + className + "] was loaded at " + new Date());
                byte[] buffer = applyTransformations(classLoadLocation, classfileBuffer, className, dataInfoProvider,
                        loader);

                return buffer;
            } else {
                System.err.println("Class [" + className + "noooo" + new Date());
                return null;
            }

        } catch (Throwable e) {
            e.printStackTrace();
            System.err.printf("[unlogged] Failed to instrument class: [%s]\n", className);
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


    private void addClassWeaveInfo(MethodVisitor mv, String base64Bytes, String probesToRecordBase64) {

        // new string on the stack
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);

        List<String> stringParts = splitString(base64Bytes, 40000);
        for (String stringPart : stringParts) {
            mv.visitLdcInsn(stringPart);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);

        }

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);

        // new string on the stack
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);

        List<String> probesToRecordParts = splitString(probesToRecordBase64, 40000);
        for (String stringPart : probesToRecordParts) {
            mv.visitLdcInsn(stringPart);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);

        }

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);


        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "io/unlogged/Runtime", "registerClass",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
    }


    private static List<String> splitString(String text, int maxLength) {
        List<String> results = new ArrayList<>();
        int length = text.length();

        for (int i = 0; i < length; i += maxLength) {
            results.add(text.substring(i, Math.min(length, i + maxLength)));
        }

        return results;
    }


}
