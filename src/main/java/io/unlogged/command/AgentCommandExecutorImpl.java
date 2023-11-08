package io.unlogged.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.unlogged.logging.IEventLogger;
import com.videobug.agent.mocking.*;
import io.unlogged.mocking.*;
import io.unlogged.util.ClassTypeUtil;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.NamingStrategy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

public class AgentCommandExecutorImpl implements AgentCommandExecutor {

    final private ObjectMapper objectMapper;
    final private IEventLogger logger;
    private final ByteBuddy byteBuddyInstance = new ByteBuddy()
            .with(new NamingStrategy.SuffixingRandom("Unlogged"));
    private final Map<String, MockInstance> globalFieldMockMap = new HashMap<>();
    Objenesis objenesis = new ObjenesisStd();

    public AgentCommandExecutorImpl(ObjectMapper objectMapper, IEventLogger logger) {
        this.objectMapper = objectMapper;
        this.logger = logger;
    }

    private static void closeHibernateSessionIfPossible(Object sessionInstance) {
        if (sessionInstance != null) {

            try {


                Method getTransactionMethod = sessionInstance.getClass().getMethod("getTransaction");
                Object transactionInstance = getTransactionMethod.invoke(sessionInstance);
//            System.err.println("Transaction to commit: " + transactionInstance);
                Method rollbackMethod = transactionInstance.getClass().getMethod("rollback");
                rollbackMethod.invoke(transactionInstance);


                Method sessionCloseMethod = sessionInstance.getClass().getMethod("close");
                sessionCloseMethod.invoke(sessionInstance);
            } catch (Exception e) {
                // failed to close session ?
            }
        }
    }

    public static Class<?>[] getAllInterfaces(Object o) {
        try {
            Set<Class<?>> results = new HashSet<>();
            getAllInterfaces(o, results::add);
            return results.toArray(new Class<?>[results.size()]);
        } catch (IllegalArgumentException e) {
            throw new AssertionError(e);
        }
    }

    public static void getAllInterfaces(Object o, Function<Class<?>, Boolean> accumulator) throws IllegalArgumentException {
        if (null == o)
            return;

        if (null == accumulator)
            throw new IllegalArgumentException("Accumulator cannot be null");

        if (o instanceof Class) {
            Class clazz = (Class) o;

            if (clazz.isInterface()) {
                if (accumulator.apply((Class) o)) {
                    for (Class aClass : clazz.getInterfaces()) {
                        getAllInterfaces(aClass, accumulator);
                    }
                }
            } else {
                if (null != clazz.getSuperclass())
                    getAllInterfaces(clazz.getSuperclass(), accumulator);

                for (Class aClass : clazz.getInterfaces()) {
                    getAllInterfaces(aClass, accumulator);
                }
            }
        } else {
            getAllInterfaces(o.getClass(), accumulator);
        }
    }

    @Override
    public AgentCommandRawResponse executeCommandRaw(AgentCommandRequest agentCommandRequest) {
        if (agentCommandRequest == null) {
            AgentCommandResponse agentCommandResponse = new AgentCommandResponse();
            agentCommandResponse.setMessage("request is null");
            agentCommandResponse.setResponseType(ResponseType.FAILED);
            return new AgentCommandRawResponse(agentCommandResponse, new Exception("request is null"));
        }
        AgentCommandResponse agentCommandResponse = new AgentCommandResponse();
        AgentCommandRawResponse agentCommandRawResponse = new AgentCommandRawResponse(agentCommandResponse);
        AgentCommandRequestType requestType = agentCommandRequest.getRequestType();
        if (requestType == null) {
            requestType = AgentCommandRequestType.REPEAT_INVOKE;
        }
        try {
            if (requestType.equals(AgentCommandRequestType.REPEAT_INVOKE)) {
                logger.setRecording(true);
            }
            Object sessionInstance = tryOpenHibernateSessionIfHibernateExists();
            try {


                Class<?> targetClassType;
                ClassLoader targetClassLoader;

                Object objectInstanceByClass = null;

                String targetClassName = agentCommandRequest.getClassName();
                objectInstanceByClass = logger.getObjectByClassName(targetClassName);
                List<String> alternateClassNames = agentCommandRequest.getAlternateClassNames();
                if (objectInstanceByClass == null && alternateClassNames != null && alternateClassNames.size() > 0) {
                    for (String alternateClassName : alternateClassNames) {
                        objectInstanceByClass = logger.getObjectByClassName(alternateClassName);
                        if (objectInstanceByClass != null) {
                            break;
                        }
                    }
                }
                ClassLoader targetClassLoader1 = logger.getTargetClassLoader();
                if (objectInstanceByClass == null) {
                    objectInstanceByClass = tryObjectConstruct(targetClassName, targetClassLoader1, new HashMap<>());
                }

                targetClassType = objectInstanceByClass != null ? objectInstanceByClass.getClass() :
                        Class.forName(targetClassName, false, targetClassLoader1);

                targetClassLoader = objectInstanceByClass != null ?
                        objectInstanceByClass.getClass().getClassLoader() : targetClassLoader1;


                List<String> methodSignatureParts = ClassTypeUtil.splitMethodDesc(
                        agentCommandRequest.getMethodSignature());

                // DO NOT REMOVE this transformation
                String methodReturnType = methodSignatureParts.remove(methodSignatureParts.size() - 1);

                List<String> methodParameters = agentCommandRequest.getMethodParameters();

                Class<?>[] expectedMethodArgumentTypes = new Class[methodSignatureParts.size()];

                for (int i = 0; i < methodSignatureParts.size(); i++) {
                    String methodSignaturePart = methodSignatureParts.get(i);
//                System.err.println("Method parameter [" + i + "] type: " + methodSignaturePart);
                    expectedMethodArgumentTypes[i] =
                            ClassTypeUtil.getClassNameFromDescriptor(methodSignaturePart, targetClassLoader);
                }


                // gets a method or throws exception if no such method
                Method methodToExecute = getMethodToExecute(targetClassType, agentCommandRequest.getMethodName(),
                        expectedMethodArgumentTypes);

                // we know more complex ways to do bypassing the security checks this thanks to lombok
                // but for now this will do
                methodToExecute.setAccessible(true);


                Class<?>[] parameterTypesClass = methodToExecute.getParameterTypes();

                Object[] parameters;
                try {
                    parameters = buildParametersUsingTargetClass(targetClassLoader, methodParameters,
                            parameterTypesClass, agentCommandRequest.getParameterTypes());
                } catch (InvalidDefinitionException ide1) {
                    if (!targetClassLoader.equals(targetClassLoader1)) {
                        parameters = buildParametersUsingTargetClass(targetClassLoader, methodParameters,
                                parameterTypesClass, agentCommandRequest.getParameterTypes());
                    } else {
                        throw ide1;
                    }
                }


                agentCommandResponse.setTargetClassName(targetClassName);
                agentCommandResponse.setTargetMethodName(agentCommandRequest.getMethodName());
                agentCommandResponse.setTargetMethodSignature(agentCommandRequest.getMethodSignature());
                agentCommandResponse.setTimestamp(new Date().getTime());

                List<DeclaredMock> declaredMocksList = agentCommandRequest.getDeclaredMocks();

                objectInstanceByClass = arrangeMocks(targetClassType, targetClassLoader, objectInstanceByClass,
                        declaredMocksList);


                Object methodReturnValue = methodToExecute.invoke(objectInstanceByClass, parameters);

                Object serializedValue = serializeMethodReturnValue(methodReturnValue);
                agentCommandResponse.setMethodReturnValue(serializedValue);

                agentCommandResponse.setResponseClassName(methodToExecute.getReturnType().getCanonicalName());
                agentCommandResponse.setResponseType(ResponseType.NORMAL);
                agentCommandRawResponse.setResponseObject(methodReturnValue);

                return agentCommandRawResponse;
            } catch (Throwable exception) {
                if (exception instanceof InvocationTargetException) {
                    agentCommandResponse.setResponseType(ResponseType.EXCEPTION);
//                    exception.getCause().printStackTrace();
                } else {
                    agentCommandResponse.setResponseType(ResponseType.FAILED);
//                    exception.printStackTrace();
                }
                Throwable exceptionCause = exception.getCause() != null ? exception.getCause() : exception;
                agentCommandResponse.setMessage(exceptionCause.getMessage());
                agentCommandRawResponse.setResponseObject(exceptionCause);
                try {
                    agentCommandResponse.setMethodReturnValue(objectMapper.writeValueAsString(exceptionCause));
                } catch (Throwable e) {
                    agentCommandResponse.setMethodReturnValue("Exception: " + exceptionCause.getMessage());
                    agentCommandResponse.setMessage("Exception: " + exceptionCause.getMessage());
                    // failed to serialize thrown exception
                }
                agentCommandResponse.setResponseClassName(exceptionCause.getClass().getCanonicalName());
            } finally {
                closeHibernateSessionIfPossible(sessionInstance);
            }
        } catch (Throwable exception) {
            exception.printStackTrace();
            Throwable exceptionCause = exception.getCause() != null ? exception.getCause() : exception;
            agentCommandResponse.setMessage(exceptionCause.getMessage());
            try {
                agentCommandRawResponse.setResponseObject(exceptionCause);
                agentCommandResponse.setMethodReturnValue(objectMapper.writeValueAsString(exceptionCause));
            } catch (Throwable e) {
                agentCommandResponse.setMethodReturnValue("Exception: " + exceptionCause.getMessage());
                agentCommandResponse.setMessage("Exception: " + exceptionCause.getMessage());
                // failed to serialize thrown exception
            }
            agentCommandResponse.setResponseClassName(exceptionCause.getClass().getCanonicalName());
            agentCommandResponse.setResponseType(ResponseType.FAILED);
        } finally {
            if (requestType.equals(AgentCommandRequestType.REPEAT_INVOKE)) {
                logger.setRecording(false);
            }
        }
        return agentCommandRawResponse;

    }

    @Override
    public AgentCommandResponse executeCommand(AgentCommandRequest agentCommandRequest) {

        AgentCommandResponse agentCommandResponse = new AgentCommandResponse();
        AgentCommandRequestType requestType = agentCommandRequest.getRequestType();
        if (requestType == null) {
            requestType = AgentCommandRequestType.REPEAT_INVOKE;
        }
        try {
            if (requestType.equals(AgentCommandRequestType.REPEAT_INVOKE)) {
                logger.setRecording(true);
            }
            Object sessionInstance = tryOpenHibernateSessionIfHibernateExists();
            try {


                Class<?> targetClassType;
                ClassLoader targetClassLoader;

                Object objectInstanceByClass = null;

                String targetClassName = agentCommandRequest.getClassName();
                objectInstanceByClass = logger.getObjectByClassName(targetClassName);
                List<String> alternateClassNames = agentCommandRequest.getAlternateClassNames();
                if (objectInstanceByClass == null && alternateClassNames != null && alternateClassNames.size() > 0) {
                    for (String alternateClassName : alternateClassNames) {
                        objectInstanceByClass = logger.getObjectByClassName(alternateClassName);
                        if (objectInstanceByClass != null) {
                            break;
                        }
                    }
                }
                ClassLoader targetClassLoader1 = logger.getTargetClassLoader();
                if (objectInstanceByClass == null) {
                    objectInstanceByClass = createObjectInstanceFromStringAndTypeInformation(
                            targetClassName, objectMapper.getTypeFactory(), "{}", Class.forName(targetClassName));
                }

                targetClassType = objectInstanceByClass != null ? objectInstanceByClass.getClass() :
                        Class.forName(targetClassName, false, targetClassLoader1);

                targetClassLoader = objectInstanceByClass != null ?
                        objectInstanceByClass.getClass().getClassLoader() : targetClassLoader1;


                List<String> methodSignatureParts = ClassTypeUtil.splitMethodDesc(
                        agentCommandRequest.getMethodSignature());

                // DO NOT REMOVE this transformation
                String methodReturnType = methodSignatureParts.remove(methodSignatureParts.size() - 1);

                List<String> methodParameters = agentCommandRequest.getMethodParameters();

                Class<?>[] expectedMethodArgumentTypes = new Class[methodSignatureParts.size()];

                for (int i = 0; i < methodSignatureParts.size(); i++) {
                    String methodSignaturePart = methodSignatureParts.get(i);
//                System.err.println("Method parameter [" + i + "] type: " + methodSignaturePart);
                    expectedMethodArgumentTypes[i] =
                            ClassTypeUtil.getClassNameFromDescriptor(methodSignaturePart, targetClassLoader);
                }


                // gets a method or throws exception if no such method
                Method methodToExecute = getMethodToExecute(targetClassType, agentCommandRequest.getMethodName(),
                        expectedMethodArgumentTypes);

                // we know more complex ways to do bypassing the security checks this thanks to lombok
                // but for now this will do
                methodToExecute.setAccessible(true);


                Class<?>[] parameterTypesClass = methodToExecute.getParameterTypes();

                Object[] parameters;
                try {
                    parameters = buildParametersUsingTargetClass(targetClassLoader, methodParameters,
                            parameterTypesClass, agentCommandRequest.getParameterTypes());
                } catch (InvalidDefinitionException ide1) {
                    if (!targetClassLoader.equals(targetClassLoader1)) {
                        parameters = buildParametersUsingTargetClass(targetClassLoader, methodParameters,
                                parameterTypesClass, agentCommandRequest.getParameterTypes());
                    } else {
                        throw ide1;
                    }
                }


                agentCommandResponse.setTargetClassName(targetClassName);
                agentCommandResponse.setTargetMethodName(agentCommandRequest.getMethodName());
                agentCommandResponse.setTargetMethodSignature(agentCommandRequest.getMethodSignature());
                agentCommandResponse.setTimestamp(new Date().getTime());

                List<DeclaredMock> declaredMocksList = agentCommandRequest.getDeclaredMocks();

                objectInstanceByClass = arrangeMocks(targetClassType, targetClassLoader, objectInstanceByClass,
                        declaredMocksList);


                Object methodReturnValue = methodToExecute.invoke(objectInstanceByClass, parameters);

                Object serializedValue = serializeMethodReturnValue(methodReturnValue);
                agentCommandResponse.setMethodReturnValue(serializedValue);

                agentCommandResponse.setResponseClassName(methodToExecute.getReturnType().getCanonicalName());
                agentCommandResponse.setResponseType(ResponseType.NORMAL);
                return agentCommandResponse;
            } catch (Throwable exception) {
                if (exception instanceof InvocationTargetException) {
                    agentCommandResponse.setResponseType(ResponseType.EXCEPTION);
//                    exception.getCause().printStackTrace();
                } else {
                    agentCommandResponse.setResponseType(ResponseType.FAILED);
//                    exception.printStackTrace();
                }
                Throwable exceptionCause = exception.getCause() != null ? exception.getCause() : exception;
                agentCommandResponse.setMessage(exceptionCause.getMessage());
                try {
                    agentCommandResponse.setMethodReturnValue(objectMapper.writeValueAsString(exceptionCause));
                } catch (Throwable e) {
                    agentCommandResponse.setMethodReturnValue("Exception: " + exceptionCause.getMessage());
                    agentCommandResponse.setMessage("Exception: " + exceptionCause.getMessage());
                    // failed to serialize thrown exception
                }
                agentCommandResponse.setResponseClassName(exceptionCause.getClass().getCanonicalName());
            } finally {
                closeHibernateSessionIfPossible(sessionInstance);
            }
        } catch (Throwable exception) {
            exception.printStackTrace();
            Throwable exceptionCause = exception.getCause() != null ? exception.getCause() : exception;
            agentCommandResponse.setMessage(exceptionCause.getMessage());
            try {
                agentCommandResponse.setMethodReturnValue(objectMapper.writeValueAsString(exceptionCause));
            } catch (Throwable e) {
                agentCommandResponse.setMethodReturnValue("Exception: " + exceptionCause.getMessage());
                agentCommandResponse.setMessage("Exception: " + exceptionCause.getMessage());
                // failed to serialize thrown exception
            }
            agentCommandResponse.setResponseClassName(exceptionCause.getClass().getCanonicalName());
            agentCommandResponse.setResponseType(ResponseType.FAILED);
        } finally {
            if (requestType.equals(AgentCommandRequestType.REPEAT_INVOKE)) {
                logger.setRecording(false);
            }
        }
        return agentCommandResponse;

    }

    @Override
    public AgentCommandResponse injectMocks(AgentCommandRequest agentCommandRequest) {

        int fieldCount = 0;
        int classCount = 0;
        Map<String, List<DeclaredMock>> mocksBySourceClass = agentCommandRequest
                .getDeclaredMocks().stream().collect(Collectors.groupingBy(DeclaredMock::getSourceClassName));


        for (String sourceClassName : mocksBySourceClass.keySet()) {
            Object sourceClassInstance = logger.getObjectByClassName(sourceClassName);
            if (sourceClassInstance == null) {
                // no instance found for this class
                // nothing to inject mocks to
                continue;
            }
            List<DeclaredMock> declaredMocks = mocksBySourceClass.get(sourceClassName);

            Map<String, List<DeclaredMock>> mocksByField = declaredMocks.stream()
                    .collect(Collectors.groupingBy(DeclaredMock::getFieldName));

            Class<? extends Object> classObject = sourceClassInstance.getClass();
            ClassLoader targetClassLoader = classObject.getClassLoader();
            String targetClassName = classObject.getCanonicalName();

            ClassLoadingStrategy<ClassLoader> strategy;


            while (classObject != Object.class) {
                classCount++;
                Field[] availableFields = classObject.getDeclaredFields();

                for (Field field : availableFields) {
                    String fieldName = field.getName();
                    if (!mocksByField.containsKey(fieldName)) {
                        continue;
                    }
                    List<DeclaredMock> declaredMocksForField = mocksByField.get(fieldName);

                    String key = sourceClassName + "#" + fieldName;
                    MockInstance existingMockInstance = globalFieldMockMap.get(key);

                    field.setAccessible(true);
                    Object originalFieldValue = null;
                    try {
                        originalFieldValue = field.get(sourceClassInstance);
                    } catch (IllegalAccessException e) {
                        // if it does happen, skip mocking of this field for now
                        System.err.println("Failed to access field [" + targetClassName + "#" + fieldName + "] " +
                                "=> " + e.getMessage());
                        continue;
                    }


                    if (existingMockInstance == null) {
                        MockHandler mockHandler = new MockHandler(declaredMocksForField, objectMapper,
                                byteBuddyInstance, objenesis, originalFieldValue, sourceClassInstance,
                                targetClassLoader, field);
                        Class<?> fieldType = field.getType();

                        DynamicType.Loaded<?> loadedMockedField;
                        loadedMockedField = createInstanceUsingByteBuddy(targetClassLoader, mockHandler, fieldType);


                        Object mockedFieldInstance = objenesis.newInstance(loadedMockedField.getLoaded());

                        existingMockInstance = new MockInstance(mockedFieldInstance, mockHandler, loadedMockedField);
                        globalFieldMockMap.put(key, existingMockInstance);

                    } else {
                        existingMockInstance.getMockHandler().setDeclaredMocks(declaredMocksForField);
                    }

                    try {
                        field.set(sourceClassInstance, existingMockInstance.getMockedFieldInstance());
                    } catch (IllegalAccessException e) {
                        System.err.println("Failed to mock field [" + sourceClassName + "#" + fieldName + "] =>" +
                                e.getMessage());
                    }
                    fieldCount++;

                }


                classObject = classObject.getSuperclass();
            }


        }

        AgentCommandResponse agentCommandResponse = new AgentCommandResponse();
        agentCommandResponse.setResponseType(ResponseType.NORMAL);
        agentCommandResponse.setMessage("Mocks injected for [" +
                fieldCount + "] fields in [" + classCount + "] classes");
        return agentCommandResponse;
    }

    
    private DynamicType.Loaded<?> createInstanceUsingByteBuddy(ClassLoader targetClassLoader, MockHandler mockHandler, Class<?> fieldType) {
        ClassLoadingStrategy.Default strategy = ClassLoadingStrategy.Default.INJECTION;
        DynamicType.Loaded<?> loadedMockedField;
        if (fieldType.isInterface()) {
            Class<?>[] implementedInterfaces = getAllInterfaces(fieldType);
            Set<String> implementedClasses = new HashSet<>();
            implementedClasses.add(fieldType.getCanonicalName());

            List<Class<?>> pendingImplementations = new ArrayList<>();
            pendingImplementations.add(fieldType);
            for (Class<?> implementedInterface : implementedInterfaces) {
                if (implementedClasses.contains(implementedInterface.getCanonicalName())) {
                    continue;
                }
                implementedClasses.add(implementedInterface.getCanonicalName());
                pendingImplementations.add(implementedInterface);
            }

            loadedMockedField = byteBuddyInstance
                    .subclass(Object.class)
                    .implement(pendingImplementations)
                    .intercept(MethodDelegation.to(mockHandler))
                    .make()
                    .load(targetClassLoader, strategy);

        } else {
            loadedMockedField = byteBuddyInstance
                    .subclass(fieldType)
                    .method(isDeclaredBy(fieldType))
                    .intercept(MethodDelegation.to(mockHandler))
                    .make()
                    .load(targetClassLoader, strategy);
        }
        return loadedMockedField;
    }

    @Override
    public AgentCommandResponse removeMocks(AgentCommandRequest agentCommandRequest) throws Exception {

        int fieldCount = 0;
        int classCount = 0;
        Set<String> strings = new HashSet<>(globalFieldMockMap.keySet());
        Set<String> classNames = new HashSet<>();
        List<DeclaredMock> mocksToDelete = agentCommandRequest.getDeclaredMocks();
        Map<String, List<DeclaredMock>> mocksByClassName = mocksToDelete == null ? new HashMap<>() :
                mocksToDelete.stream().collect(Collectors.groupingBy(DeclaredMock::getSourceClassName));
        if (mocksByClassName.size() > 0) {
            // remove some mocks
            for (String sourceClassName : mocksByClassName.keySet()) {
                Map<String, List<DeclaredMock>> classMocksByFieldName = mocksByClassName
                        .get(sourceClassName).stream()
                        .collect(Collectors.groupingBy(DeclaredMock::getFieldName));
                for (String fieldName : classMocksByFieldName.keySet()) {
                    String key = sourceClassName + "#" + fieldName;
                    MockInstance mockInstance = globalFieldMockMap.get(key);
                    if (mockInstance == null) {
                        continue;
                    }
                    if (!classNames.contains(sourceClassName)) {
                        classNames.add(sourceClassName);
                        classCount++;
                    }
                    fieldCount++;
                    MockHandler mockHandler = mockInstance.getMockHandler();
                    mockHandler.removeDeclaredMock(classMocksByFieldName.get(fieldName));
                }

            }

        } else {
            // remove all mocks
            for (String key : strings) {
                fieldCount++;
                MockInstance mockInstance = globalFieldMockMap.get(key);
                MockHandler mockHandler = mockInstance.getMockHandler();
                Object parentInstance = mockHandler.getOriginalFieldParent();
                String sourceObjectTypeName = parentInstance.getClass().getCanonicalName();
                if (!classNames.contains(sourceObjectTypeName)) {
                    classNames.add(sourceObjectTypeName);
                    classCount++;
                }
                Object originalFieldInstance = mockHandler.getOriginalImplementation();
                Field field = mockHandler.getField();
                field.setAccessible(true);
                field.set(parentInstance, originalFieldInstance);
                globalFieldMockMap.remove(key);
            }

        }

        AgentCommandResponse agentCommandResponse = new AgentCommandResponse();
        agentCommandResponse.setResponseType(ResponseType.NORMAL);
        agentCommandResponse.setMessage("Mocks removed for [" +
                fieldCount + "] fields in [" + classCount + "] classes");
        return agentCommandResponse;

    }

    public Object arrangeMocks(Class<?> targetClassType, ClassLoader targetClassLoader,
                               Object objectInstanceByClass, List<DeclaredMock> declaredMocksList) {
        if (declaredMocksList == null || declaredMocksList.size() == 0) {
            return objectInstanceByClass;
        }

        Map<String, MockInstance> mockInstanceMap = new HashMap<>();

        String targetClassName = targetClassType.getCanonicalName();
        Map<String, List<DeclaredMock>> mocksByFieldName = declaredMocksList.stream()
                .collect(Collectors.groupingBy(DeclaredMock::getFieldName,
                        Collectors.toList()));

        DynamicType.Unloaded<? extends Object> extendedClass = byteBuddyInstance
                .subclass(targetClassType)
                .make();

        ClassLoadingStrategy<ClassLoader> strategy;
        if (ClassInjector.UsingLookup.isAvailable()) {
            Class<?> methodHandles = null;
            try {
                methodHandles = targetClassLoader.loadClass("java.lang.invoke.MethodHandles");
                Object lookup = methodHandles.getMethod("lookup").invoke(null);
                Method privateLookupIn = methodHandles.getMethod("privateLookupIn",
                        Class.class,
                        targetClassLoader.loadClass("java.lang.invoke.MethodHandles$Lookup"));
                Object privateLookup = privateLookupIn.invoke(null, targetClassType, lookup);
                strategy = ClassLoadingStrategy.UsingLookup.of(privateLookup);
            } catch (Exception e) {
                // should never happen
                throw new RuntimeException(e);
            }
        } else if (ClassInjector.UsingReflection.isAvailable()) {
            strategy = ClassLoadingStrategy.Default.INJECTION;
        } else {
            throw new IllegalStateException("No code generation strategy available");
        }

        DynamicType.Loaded<? extends Object> extendedClassLoaded = extendedClass.load(targetClassLoader, strategy);

        Object extendedClassInstance = objenesis.newInstance(extendedClassLoaded.getLoaded());


        Class<?> fieldCopyForClass = targetClassType;
        while (fieldCopyForClass != null && !fieldCopyForClass.equals(Object.class)) {
            Field[] declaredFields = fieldCopyForClass.getDeclaredFields();

            for (Field field : declaredFields) {
                try {

                    field.setAccessible(true);
                    List<DeclaredMock> declaredMocksForField = mocksByFieldName.get(field.getName());

                    Object fieldValue = field.get(objectInstanceByClass);
                    boolean fieldTypeIsFinal = Modifier.isFinal(field.getType().getModifiers());
                    if (declaredMocksForField == null || declaredMocksForField.size() == 0 || fieldTypeIsFinal) {
                        if (fieldValue == null) {
                            try {
                                fieldValue = objenesis.newInstance(field.getType());
                            } catch (Throwable e) {
                                continue;
                            }
                        }
                        if (Modifier.isFinal(field.getModifiers())) {
                            continue;
                        }
                        field.set(extendedClassInstance, fieldValue);
                    } else {

                        String key = targetClassName + "#" + field.getName();
                        MockInstance existingMockInstance = mockInstanceMap.get(key);


                        if (existingMockInstance == null) {

                            if (objectInstanceByClass == null) {
                                System.err.println("original instance is null [" + field.getType()
                                        .getCanonicalName() + " " + field.getName());
                            }

                            if (fieldValue == null) {
                                fieldValue = createObjectInstanceFromStringAndTypeInformation(
                                        field.getType().getCanonicalName(), objectMapper.getTypeFactory(), "{}",
                                        field.getType()
                                );
                            }

                            existingMockInstance = createMockedInstance(targetClassLoader, objectInstanceByClass,
                                    field, declaredMocksForField, fieldValue, field.getType());
                            mockInstanceMap.put(key, existingMockInstance);

                        } else {
                            existingMockInstance.getMockHandler().setDeclaredMocks(declaredMocksForField);
                        }

//                        System.out.println("Setting mocked field [" + field.getName() + "] => " + existingMockInstance.getMockedFieldInstance());
                        field.set(extendedClassInstance, existingMockInstance.getMockedFieldInstance());
                    }


                } catch (Exception e) {
                    if (e.getMessage().startsWith("Can not set static final")) {
                        // not printing this
                    } else {
                        e.printStackTrace();
                        System.err.println(
                                "Failed to set value for field [" + field.getName() + "] => " + e.getMessage());
                    }
                }
            }

            fieldCopyForClass = fieldCopyForClass.getSuperclass();
        }
        return extendedClassInstance;
    }

    
    private MockInstance createMockedInstance(
            ClassLoader targetClassLoader,
            Object objectInstanceByClass, Field field,
            List<DeclaredMock> declaredMocksForField,
            Object fieldValue, Class<?> fieldType) {
        ClassLoadingStrategy<ClassLoader> strategy = ClassLoadingStrategy.Default.INJECTION;
        MockInstance existingMockInstance;
        if (objectInstanceByClass == null) {
            if (field != null) {
                System.out.println(
                        "objectInstanceByClass is null: " + field.getType().getCanonicalName() + " " + field.getName());
            } else {
            }
        }
        MockHandler mockHandler = new MockHandler(declaredMocksForField, objectMapper,
                byteBuddyInstance, objenesis, fieldValue, objectInstanceByClass, targetClassLoader, field);

        DynamicType.Loaded<?> loadedMockedField;
        if (fieldType.isInterface()) {
            Class<?>[] implementedInterfaces = getAllInterfaces(fieldType);
            Set<String> implementedClasses = new HashSet<>();
            implementedClasses.add(fieldType.getCanonicalName());

            List<Class<?>> pendingImplementations = new ArrayList<>();
            pendingImplementations.add(fieldType);
            for (Class<?> implementedInterface : implementedInterfaces) {
                if (implementedClasses.contains(implementedInterface.getCanonicalName())) {
                    continue;
                }
                implementedClasses.add(implementedInterface.getCanonicalName());
                pendingImplementations.add(implementedInterface);
            }

            loadedMockedField = byteBuddyInstance
                    .subclass(Object.class)
                    .implement(pendingImplementations)
                    .intercept(MethodDelegation.to(mockHandler))
                    .make()
                    .load(targetClassLoader, strategy);

        } else {
            loadedMockedField = byteBuddyInstance
                    .subclass(fieldType)
                    .method(isDeclaredBy(fieldType)).intercept(MethodDelegation.to(mockHandler))
                    .make()
                    .load(targetClassLoader, strategy);
        }


        Object mockedFieldInstance = objenesis.newInstance(loadedMockedField.getLoaded());

        existingMockInstance = new MockInstance(mockedFieldInstance, mockHandler, loadedMockedField);
        return existingMockInstance;
    }

    private Method getMethodToExecute(Class<?> objectClass, String expectedMethodName,
                                      Class<?>[] expectedMethodArgumentTypes)
            throws NoSuchMethodException {

        String className = objectClass.getCanonicalName();

        Method methodToExecute = null;
        List<String> methodNamesList = new ArrayList<>();
        while (objectClass != null && !objectClass.equals(Object.class)) {

            try {
                methodToExecute = objectClass
                        .getDeclaredMethod(expectedMethodName, expectedMethodArgumentTypes);
            } catch (NoSuchMethodException ignored) {

            }

            try {
                methodToExecute = objectClass
                        .getMethod(expectedMethodName, expectedMethodArgumentTypes);
            } catch (NoSuchMethodException ignored) {

            }

            if (methodToExecute == null) {
                Method[] methods = objectClass.getDeclaredMethods();
                for (Method method : methods) {
                    String methodName = method.getName();
                    methodNamesList.add(methodName);
                    if (methodName.equals(expectedMethodName)
                            && method.getParameterCount() == expectedMethodArgumentTypes.length) {

                        Class<?>[] actualParameterTypes = method.getParameterTypes();

                        boolean match = true;
                        for (int i = 0; i < expectedMethodArgumentTypes.length; i++) {
                            Class<?> methodParameterType = expectedMethodArgumentTypes[i];
                            Class<?> actualParamType = actualParameterTypes[i];
                            if (!actualParamType.getCanonicalName()
                                    .equals(methodParameterType.getCanonicalName())) {
                                match = false;
                                break;
                            }
                        }

                        if (match) {
                            methodToExecute = method;
                            break;
                        }

                    }
                }
            }
            if (methodToExecute != null) {
                break;
            }
            objectClass = objectClass.getSuperclass();
        }
        if (methodToExecute == null) {
            System.err.println("Method not found: " + expectedMethodName
                    + ", methods were: " + methodNamesList);
            throw new NoSuchMethodException("method not found [" + expectedMethodName
                    + "] in class [" + className + "]. Available methods are: "
                    + methodNamesList);
        }

        return methodToExecute;
    }

    private Object serializeMethodReturnValue(Object methodReturnValue) throws JsonProcessingException {
        if (methodReturnValue == null) {
            return null;
        }
        if (methodReturnValue instanceof Double) {
            return Double.doubleToLongBits((Double) methodReturnValue);
        } else if (methodReturnValue instanceof Float) {
            return Float.floatToIntBits((Float) methodReturnValue);
//                    } else if (methodReturnValue instanceof Flux) {
//                        Flux<?> returnedFlux = (Flux<?>) methodReturnValue;
//                        agentCommandResponse.setMethodReturnValue(
//                                objectMapper.writeValueAsString(returnedFlux.collectList().block()));
//                    } else if (methodReturnValue instanceof Mono) {
//                        Mono<?> returnedFlux = (Mono<?>) methodReturnValue;
//                        agentCommandResponse.setMethodReturnValue(
//                                objectMapper.writeValueAsString(returnedFlux.block()));
        } else if (methodReturnValue instanceof String) {
            return methodReturnValue;
        } else {
            try {
                if (methodReturnValue instanceof Flux) {
                    Flux<?> returnedFlux = (Flux<?>) methodReturnValue;
                    return objectMapper.writeValueAsString(returnedFlux.collectList().block());
                } else if (methodReturnValue instanceof Mono) {
                    Mono<?> returnedFlux = (Mono<?>) methodReturnValue;
                    return objectMapper.writeValueAsString(returnedFlux.block());
                }
                return objectMapper.writeValueAsString(methodReturnValue);
            } catch (Exception ide) {
                return "Failed to serialize response object of " +
                        "type: " + (methodReturnValue.getClass() != null ?
                        methodReturnValue.getClass().getCanonicalName() : methodReturnValue);
            }
        }
    }

    private Object[] buildParametersUsingTargetClass(
            ClassLoader targetClassLoader,
            List<String> methodParameters,
            Class<?>[] parameterTypesClass,
            List<String> parameterTypes) throws JsonProcessingException {
        TypeFactory typeFactory = objectMapper.getTypeFactory().withClassLoader(targetClassLoader);
        Object[] parameters = new Object[methodParameters.size()];

        for (int i = 0; i < methodParameters.size(); i++) {
            String methodParameterStringValue = methodParameters.get(i);
            Class<?> parameterType = parameterTypesClass[i];
            String parameterTypeName = parameterTypes.get(i);
            Object parameterObject = createObjectInstanceFromStringAndTypeInformation(parameterTypeName, typeFactory,
                    methodParameterStringValue, parameterType);

            parameters[i] = parameterObject;
        }
        return parameters;
    }

    
    private Object createObjectInstanceFromStringAndTypeInformation
            (String stringParameterType, TypeFactory typeFactory, String methodParameter, Class<?> parameterType) throws JsonProcessingException {
        Object parameterObject = null;
        if (parameterType.getCanonicalName().equals("org.springframework.util.MultiValueMap")) {
            try {
                parameterObject = objectMapper.readValue(methodParameter,
                        Class.forName("org.springframework.util.LinkedMultiValueMap"));
            } catch (ClassNotFoundException e) {
                // this should never happen
            }
        } else {

            JavaType typeReference = null;
            try {
                if (stringParameterType != null) {
                    typeReference = MockHandler.getTypeReference(typeFactory, stringParameterType);
                }
            } catch (Throwable e) {
                // failed to construct from the canonical name,
                // happens when this is a generic type
                // so we try to construct using type from the method param class

            }
            if (typeReference == null) {
                typeReference = typeFactory.constructType(parameterType);
            }
            try {
                parameterObject = objectMapper.readValue(methodParameter, typeReference);
            } catch (Throwable e) {
                // a complicated type (no default args constructor), or interface which jackson cannot create ?
                try {
                    // can we try using objenesis ?
                    parameterObject = createParameterUsingObjenesis(typeReference, methodParameter, typeFactory);
                    // we might want to now construct the whole object tree deep down
                } catch (Throwable e1) {
                    // constructing using objenesis also failed
                    // lets try extending or implementing the class ?
                    parameterObject = createParameterUsingMocking(methodParameter, parameterType);
                }
            }
        }
        return parameterObject;
    }

    private Object createParameterUsingObjenesis(JavaType typeReference, String methodParameter, TypeFactory typeFactory)
            throws JsonProcessingException, IllegalAccessException {
        Class<?> rawClass = typeReference.getRawClass();
        Object parameterObject = objenesis.newInstance(rawClass);
        Class<?> currentClass = rawClass;
        JsonNode providedValues = objectMapper.readTree(methodParameter);
        while (!currentClass.equals(Object.class)) {
            Field[] declaredFields = rawClass.getDeclaredFields();
            for (Field declaredField : declaredFields) {
                JsonNode fieldValueInNodeByName = providedValues.get(declaredField.getName());
                Object valueToSet = getValueToSet(typeFactory, fieldValueInNodeByName, declaredField.getType());
                declaredField.setAccessible(true);
                declaredField.set(parameterObject, valueToSet);
            }
            currentClass = currentClass.getSuperclass();
        }
        return parameterObject;
    }

    
    private Object createParameterUsingMocking(String methodParameter, Class<?> parameterType) throws JsonProcessingException {
        Class<?> currentClass;
        List<DeclaredMock> mockList = new ArrayList<>();

        JsonNode providedValues = objectMapper.readTree(methodParameter);
        currentClass = parameterType;
        while (currentClass != null && !currentClass.equals(Object.class)) {
            Method[] definedMethods = currentClass.getDeclaredMethods();
            for (Method definedMethod : definedMethods) {
                String methodName = definedMethod.getName();
                String potentialFieldName = null;
                Class<?> valueType = null;
                if (methodName.startsWith("get") && definedMethod.getParameterTypes().length == 0) {
                    potentialFieldName = methodName.substring(3);
                    valueType = definedMethod.getReturnType();
//                            } else if (methodName.startsWith("set") && definedMethod.getParameterTypes().length == 1) {
//                                valueType = definedMethod.getParameterTypes()[0];
//                                potentialFieldName = methodName.substring(3);
                } else if (methodName.startsWith("is") && definedMethod.getParameterTypes().length == 0) {
                    valueType = definedMethod.getReturnType();
                    potentialFieldName = methodName.substring(2);
                }

                if (potentialFieldName != null && providedValues.has(potentialFieldName)) {
                    JsonNode providedValue = providedValues.get(potentialFieldName);
//                                Object valueToReturn = getValueToSet(typeFactory, providedValue, valueType);

                    if (methodName.startsWith("get") || methodName.startsWith("is")) {
                        ArrayList<ThenParameter> thenParameterList = new ArrayList<>();
                        ReturnValue returnParameter = new ReturnValue(providedValue.asText(),
                                valueType.getCanonicalName(), ReturnValueType.REAL);
                        thenParameterList.add(new ThenParameter(returnParameter, MethodExitType.NORMAL));
                        DeclaredMock dummyMockDefinition = new DeclaredMock(
                                "generated mock for " + methodName, valueType.getCanonicalName(),
                                potentialFieldName, methodName, new ArrayList<>(), thenParameterList
                        );
                        mockList.add(dummyMockDefinition);
                    }
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        MockInstance parameterObject = createMockedInstance(parameterType.getClassLoader(), null,
                null, mockList, null, parameterType);
        return parameterObject.getMockedFieldInstance();
    }

    
    private Object getValueToSet(TypeFactory typeFactory, JsonNode fieldValueInNodeByName, Class<?> type) throws JsonProcessingException {
        Object valueToSet = null;
        if (int.class.equals(type) || Integer.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.intValue();
        } else if (long.class.equals(type) || Long.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.longValue();
        } else if (double.class.equals(type) || Double.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.doubleValue();
        } else if (float.class.equals(type) || Float.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.floatValue();
        } else if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.booleanValue();
        } else if (short.class.equals(type) || Short.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.shortValue();
        } else if (String.class.equals(type)) {
            valueToSet = fieldValueInNodeByName.textValue();
        } else if (StringBuilder.class.equals(type)) {
            valueToSet = new StringBuilder(fieldValueInNodeByName.textValue());
        } else {
            valueToSet = createObjectInstanceFromStringAndTypeInformation(
                    null, typeFactory, fieldValueInNodeByName.asText(), type);
        }
        return valueToSet;
    }


    private Object tryObjectConstruct(String className, ClassLoader targetClassLoader, Map<String, Object> buildMap)
            throws InstantiationException, IllegalAccessException {
        if (className.equals("java.util.List")) {
            return new ArrayList<>();
        }
        if (className.equals("java.util.Map")) {
            return new HashMap<>();
        }
        Object newInstance = null;
        if (targetClassLoader == null) {
            System.err.println("Failed to construct instance of class [" + className + "]. classLoader is not defined");
        }
        Class<?> loadedClass;
        try {
            loadedClass = targetClassLoader.loadClass(className);
        } catch (ClassNotFoundException classNotFoundException) {
            // class not found
            // or this is an internal class ? try to check one level up class ?
            if (className.lastIndexOf(".") == -1) {
                // todo: if it was an array of an internal class
                // com.something.package.ParentClass$ChildClass[][]
                return null;
            }
            String parentName = className.substring(0, className.lastIndexOf("."));
            try {
                Class<?> parentClassType = targetClassLoader.loadClass(parentName);
                // if we found this, then
                loadedClass =
                        targetClassLoader.loadClass(
                                parentName + "$" + className.substring(className.lastIndexOf(".") + 1));

            } catch (ClassNotFoundException cne) {
                // try another level ? just to be sure ?better way to identify internal classes ?
                return null;
            }
        }
        Constructor<?> noArgsConstructor = null;
        try {
            noArgsConstructor = loadedClass.getConstructor();
            try {
                newInstance = noArgsConstructor.newInstance();
            } catch (InvocationTargetException e) {
//                throw new RuntimeException(e);
            }
        } catch (NoSuchMethodException e) {
            //
        }
        if (newInstance == null) {
            Method[] methods = loadedClass.getMethods();
            // try to get the instance of the class using Singleton.getInstance
            for (Method method : methods) {
                if (method.getParameterCount() == 0 && Modifier.isStatic(method.getModifiers())) {
                    if (method.getReturnType().equals(loadedClass)) {
                        try {
                            return method.invoke(null);
                        } catch (InvocationTargetException ex) {
                            // this method for potentially getting instance from static getInstance type method
                            // did not work
                        }
                    }
                }
            }
        }
        if (newInstance == null) {
            try {
                newInstance = objenesis.newInstance(loadedClass);
            } catch (InstantiationError e) {
                // failed to create using objenesis
            }
        }

        if (newInstance == null) {
            try {
                MockHandler mockHandler = new MockHandler(new ArrayList<>(), objectMapper, byteBuddyInstance,
                        objenesis, null, null, targetClassLoader, null);
                DynamicType.Loaded<?> newInstanceLoader = createInstanceUsingByteBuddy(targetClassLoader, mockHandler,
                        loadedClass);
                newInstance = objenesis.newInstance(newInstanceLoader.getLoaded());

            } catch (Exception exception) {
                // failed to create using bytebuddy also
                //
            }
        }

        buildMap.put(className, newInstance);
        if (newInstance == null) {
            return newInstance;
        }

        // field injections
        Class<?> fieldsForClass = loadedClass;

        while (fieldsForClass != null && !fieldsForClass.equals(Object.class)) {
            Field[] fields = fieldsForClass.getDeclaredFields();


            for (Field field : fields) {

                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Exception e) {
                    continue;
                }

                String fieldTypeName = field.getType().getCanonicalName();
                Object value = field.get(newInstance);
                if (value != null) {
                    continue;
                }
                if (buildMap.containsKey(fieldTypeName)) {
                    value = buildMap.get(fieldTypeName);
                } else {
                    value = tryObjectConstruct(fieldTypeName, targetClassLoader, buildMap);
                    if (value == null) {
                        continue;
                    }
                    buildMap.put(className, value);
                }
                try {
                    field.set(newInstance, value);
                } catch (Throwable th) {
                    th.printStackTrace();
                    System.out.println("Failed to set field value: " + th.getMessage());
                }

            }


            fieldsForClass = fieldsForClass.getSuperclass();
        }

        return newInstance;

    }


    private Object tryOpenHibernateSessionIfHibernateExists() {
        Object hibernateSessionFactory = logger.getObjectByClassName("org.hibernate.internal.SessionFactoryImpl");
        Object sessionInstance = null;
        if (hibernateSessionFactory != null) {
            try {


//            System.err.println("Hibernate session factory: " + hibernateSessionFactory);
                Method openSessionMethod = hibernateSessionFactory.getClass().getMethod("openSession");
                sessionInstance = openSessionMethod.invoke(hibernateSessionFactory);
//            System.err.println("Hibernate session opened: " + sessionInstance);
                Class<?> managedSessionContextClass = Class.forName(
                        "org.hibernate.context.internal.ManagedSessionContext");
                Method bindMethod = managedSessionContextClass.getMethod("bind",
                        Class.forName("org.hibernate.Session"));
                bindMethod.invoke(null, sessionInstance);


                Method beginTransactionMethod = sessionInstance.getClass().getMethod("beginTransaction");
                beginTransactionMethod.invoke(sessionInstance);

            } catch (Exception e) {
                // failed to create hibernate session
                return null;
            }

        }
        return sessionInstance;
    }

}
