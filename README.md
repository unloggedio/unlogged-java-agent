
# SELogger

SELogger is a Java Agent to record an execution trace of a Java program.
The tool name "SE" means Software Engineering, because the tool is developed for software engineering research topics including omniscient debugging. 

The design of this tool is partly explained in the following article.
- Kazumasa Shimari, Takashi Ishio, Tetsuya Kanda, Naoto Ishida, Katsuro Inoue: "NOD4J: Near-omniscient debugging tool for Java using size-limited execution trace", Science of Computer Programming, Volume 206, 2021, 102630, ISSN 0167-6423, https://doi.org/10.1016/j.scico.2021.102630.


## Build

Build a jar file with Maven.

        mvn package

SELogger uses ASM (http://asm.ow2.org/) for injecting logging code.
The class names are shaded by maven-shade-plugin so that 
SELogger can manipulate a program using ASM. 

### How to Build for JDK7

selogger works with JDK 7 while selogger's test cases requires JDK 8 to test the behavior of INVOKEDYNAMIC instructions.
If you would like to build a jar file for JDK7, please skip compilation of test classes as follows.
  - Prepare JDK 7 and Maven.
  - Replace the JDK version `1.8` with `1.7` for the `maven-compiler-plugin` in `pom.xml`.
  - Execute `mvn package -Dmaven.test.skip=true`.

## Usage

Execute your program with the Java Agent.

        java -javaagent:path/to/selogger-0.2.3.jar [Application Options]

The agent accepts options.  Each option is specified by `option=value` style with commas (","). For example:

        java -javaagent:path/to/selogger-0.2.3.jar=output=dirname,format=freq [Application Options]


### Output Options

The `output=` option specifies a directory to store an execution trace.  
The directory is automatically created if it does not exist.
The default output directory is `selogger-output`.

The `format=` option specifies an output format.  The default is `latest` format.
  * `freq` mode records only a frequency table of events.
  * `latest` mode records the latest event data with timestamp and thread ID for each bytecode location. 
  * `nearomni` mode is an alias of `latest`.
  * `latest-simple` mode records only the latest event data for each bytecode location.
  * `omni` mode records all the events in a stream.
  * `discard` mode discard event data, while it injects logging code into classes.

In `latest` and `latesttime` mode, two additional options are available:
  * `size=` specifies the size of buffers.  The default is 32.
  * `keepobj=false` keeps objects using weak references to avoid the impact of GC.  It reduces memory consumption, while some object information may be lost.


### Logging Target Event Options

The `weave=` option specifies events to be recorded. Supported event groups are: 

  * EXEC (entry/exit)
  * CALL (call)
  * PARAM (parameters for method entries and calls)
  * FIELD (field access)
  * ARRAY (array creation and access)
  * OBJECT (constant object usage)
  * SYNC (synchronized blocks)
  * LOCAL (local variables)
  * LABEL (conditional branches)

The default configuration records all events. 

Note: The event category names EXEC and CALL come from AspectJ pointcut: execution and call.


### Excluding Libraries from Logging

#### Filtering by Package/Class Name

Logging may generate a huge amount of events.  
You can add a prefix of class names whose behavior is excluded from the logging process using `e=` option.  

You can use multiple `e=` options to enumerate class paths.
By default, the selogger excludes the system classes from logging: `sun/`,`com/sun/`, `java/`, and `javax/`.

#### Filtering by File Location

If you would like to exclude classes loaded from a particular directory/JAR file, you can specify a location whose behavior is excluded from the logging process using `exlocation=` option.
Each class has a URI where the class is loaded.  If the path contains a specified substring by this option, that class is excluded from logging.
You can find the actual URI in the `log.txt` file generated by SELogger.  
A line `Weave: [Class Name] loaded from [URI]` shows a pair of class name and its location.

You can use multiple `exlocation=` options to enumerate file paths.


#### Infinite loop risk 

The security manager mechanism of Java Virtual Machine may call a `checkPermission` method to check whether a method call is allowed in the current context or not.
When a checkPermission method is called, logging code tries to record logging information.  The step triggers an additional checkPermission call, and results in infinite recursive calls.

A workaround is: Excluding a security manager from bytecode weaving using the `e=` option.
The following example is for JUnit test of Jackson DataBind program in the `defects4j` benchmark.

        <jvmarg value="-javaagent:/data/selogger/target/selogger-0.2.3.jar=format=omni,e=com/fasterxml/jackson/databind/misc/AccessFixTest$CauseBlockingSecurityManager" />


### Option for Troubleshooting

The `dump=true` option stores class files including logging code into the output directory. It may help a debugging task if invalid bytecode is generated. 


## Package Structure

SELogger comprises three sub-packages: `logging`, `reader`, and `weaver`.

  - The `weaver` sub-package is an implementation of Java Agent.  
    - RuntimeWeaver class is the entry point of the agent.  It calls ClassTransformer to inject logging instructions into target classes.
  - The `logging` sub-package implements logging features.
    - Logging class is the entry point of the logging feature.  It records runtime events in files.
  - The `reader` sub-package implements classes to read log files.
    - LogPrinter class is an example to read `.slg` files generated by logging classes. 


## Runtime Events

The runtime events are listed in `selogger.EventType` class. 
Each event is recorded before/after a particular bytecode instruction is executed.
Hence, SELogger assigns `data ID` to each event based on the bytecode location (class, method, and instruction position in the method).
Using the data ID, users can distinguish events generated by different bytecode instructions.

Each runtime event has static attributes, e.g. method name called by a method call instruction.
Those attributes are stored in `dataids.txt` in the output directory.

The dynamic information, data recorded with events are stored in a trace file.
The default `nearomni` option generates a text file named `recentdata.txt` whose line includes the following data items in a CSV format:
 - Data ID representing an event
 - The number of the events observed in the execution 
 - The number of events recorded in the file
 - A list of recorded values (triples of a data value, a sequential number representing the order of recording, and a thread ID) for the events

The following table is a list of events.
The event name is defined in the class `EventType`.  


|Event Category         |Event Name                |Timing       |Recorded Data|
|:----------------------|:-------------------------|:------------|:--------------------|
|Method Execution (EXEC)|METHOD_ENTRY              |The method is entered, before any instructions in the method is executed|Receiver object if the method is an instance method|
|                       |METHOD_PARAM              |Immediately after METHOD_ENTRY, before any instructions in the method is executed.  The number of the events is the same as the number of formal parameters.|Parameter given to the method|
|                       |METHOD_NORMAL_EXIT        |Before a return instruction (one of RETURN, IRETURN, ARETURN, LRETURN, FRETURN, or DRETURN) is executed|Returned value from the method (= The value passed to the return instruction)|
|                       |METHOD_EXCEPTIONAL_EXIT   |When the method is exiting by an exception|Exception thrown from the method to the caller| 
|                       |METHOD_OBJECT_INITIALIZED |Immediately after execution of `this()` or `super()` in a constructor, before any other instructions in the constructor is executed|Object initialized by the constructor|
|                       |METHOD_THROW              |Before a throw instruction (ATHROW) is executed|Exception thrown by the throw statement|
|Method Call (CALL)     |CALL                      |Before a method is called by a method call instruction (one of INVOKEVIRTUAL, INVOKESTATIC, and INVOKESPECIAL)|Receiver object|
|                       |CALL_PARAM                |Immediately after a CALL event, before executing the method invocation.  The number of the events is the same as the number of actual parameters.|Parameter passed to the callee|
|                       |CALL_RETURN               |After a method invocation instruction is executed|Returned value from the callee|
||NEW_OBJECT|When a `new` statement created an instance of some class.|This event does NOT record the object because the created object is not initialized (i.e. not accessible) at this point of execution.|
||NEW_OBJECT_CREATED|After a constructor call is finished.|Object initialized by the constructor call|
||INVOKE_DYNAMIC|Before INVOKEDYNAMIC instruction creates a function object.|(None)|
||INVOKE_DYNAMIC_PARAM|Immediately after INVOKE_DYNAMIC event.  |Parameter passed to INVOKEDYNAMIC instruction.|
||INVOKE_DYNAMIC_RESULT|After the INVOKEDYNAMIC instruction.|A function object created by the INVOKEDYNAMIC instruction.|
|Field access (FIELD)|GET_INSTANCE_FIELD|Before the instance field is read by a GETFIELD instruction|Object whose field is read|
|                    |GET_INSTANCE_FIELD_RESULT|After the field is read by a GETFIELD instruction|Value read from the field|
|                    |GET_STATIC_FIELD|After the static field is read by a GETSTATIC instruction|Value read from the field|
|                    |PUT_INSTANCE_FIELD|Before the instance field is written by a PUTFIELD instruction|Object whose field is written|
|                    |PUT_INSTANCE_FIELD_VALUE|Immediately after PUT_INSTANCE_FIELD, before the instance field is written|Value written to the field|
|                    |PUT_INSTANCE_FIELD_BEFORE_INITIALIZATION|Before the instance field is written by a PUTFIELD instruction.  This event is used when the object has not been initialized by a constructor but whose field is assigned; for example, when an anonymous inner class object stores the external context into its filed.|Value written to the field|
|                    |PUT_STATIC_FIELD|Before the static field is written by a PUTSTATIC instruction|Value written to the field|
|Array access (ARRAY)|ARRAY_LOAD|Before a value is read from the array by an array load instruction (one of AALOAD, BALOAD, CALOAD, DALOAD, FALOAD, IALOAD, LALOAD, and SALOAD)|Accessed array to read|
|                    |ARRAY_LOAD_INDEX|Immediately after ARRAY_LOAD event.|Accessed index to read|
|                    |ARRAY_LOAD_RESULT|After a value is loaded from the array.|Value read from the array|
|                    |ARRAY_STORE|Before a value is written to the array by an array store instruction (one of AASTORE, BASTORE, CASTORE, DASTORE, FASTORE, IASTORE, LASTORE, and SASTORE)|Accessed array to write|
|                    |ARRAY_STORE_INDEX|Immediately after ARRAY_STORE event|Accessed index to write|
|                    |ARRAY_STORE_VALUE|Immediately after ARRAY_STORE_INDEX event|Value written to the array|
|                    |NEW_ARRAY|Before an array is created.|Length of the new array to be created|
|                    |NEW_ARRAY_RESULT|After an array is created.|Created array object|
|                    |MULTI_NEW_ARRAY|After a multi-dimendioanl array is created.|Created array object|
|                    |MULTI_NEW_ARRAY_OWNER|A sequence of MULTI_NEW_ARRAY_OWNER followed by MULTI_NEW_ARRAY_ELEMENT events are recorded immediately after a MULTI_NEW_ARRAY event.  The events represent a recursive structure of multi-dimensional arrays (An OWNER array has a number of ELEMENT arrays).|An array object which contains array objects|
|                    |MULTI_NEW_ARRAY_ELEMENT (`new` instruction for arrays)|This event is generated to record an array object contained in an owner array.|Array object contained in the owner array.|
|                    |ARRAY_LENGTH|Before the length of the array is read by an ARRAYLENGTH instruction|Array whose length is referred.   This may be null.|
|                    |ARRAY_LENGTH_RESULT|After the execution of the ARRAYLENGTH instruction. |The length of the array|
|Synchronized block (SYNC)|MONITOR_ENTER|When a thread of control reached the synchronized block, before entering the block.|Object to be locked by the synchronized block.|
|                         |MONITOR_ENTER_RESULT|When a thread of control entered the synchronized block, before any instructions in the block is executed|Object locked by the synchronized block.|
|                         |MONTIOR_EXIT|When a thread of control is exiting the synchronized block.  |Object to be unlocked by the block|
|Object manipulation (OBJECT)|OBJECT_CONSTANT_LOAD|When a constant object (usually String) is loaded by the instruction.|Object loaded by the instruction.  This may be null.|
|                            |OBJECT_INSTANCEOF|Before the INSTANCEOF instruction is executed.|Object whose class is checked by the instruction|
|                            |OBJECT_INSTANCEOF_RESULT|After the INSTANCEOF instruction is executed.|Result (true or false) of the instruction|
|Local variables (LOCAL)|LOCAL_LOAD|Before the value of the local variable is read by a local variable instruction (one of ALOD, DLOAD, FLOAD, ILOAD, and LLOAD)|Value read from the variable|
|                       |LOCAL_STORE|Before the value is written to the local variable by an instruction (one of ASTORE, DSTORE, FSTORE, ISTORE, and LSTORE) |Value written to the variable|
|                       |LOCAL_INCREMENT|After the local variable is updated by an IINC instruction.  An IINC instruction is not only for `i++`; it is also used for `i+=k` and `i-=k` if `k` is constant (depending on a compiler).|Value written to the variable by an increment instruction|
|                       |RET|This event corresponds to a RET instruction for subroutine call.  The current version does not generate this event.||
|Control-flow events (LABEL)|LABEL|This event is recorded when an execution passed a particular code location. LABEL itself is not a Java bytecode, a pseudo instruction inserted by ASM bytecode manipulation library used by SELogger.|A dataId corresponding to the previous program location is recorded so that a user can trace a control-flow path.|
|                           |CATCH_LABEL|This event is recorded when an execution entered a catch/finally block.|A dataId corresponding to the previous program location (that is likely where an exception was thrown) is recorded.|
|                           |CATCH|When an execution entered a catch/finally block, immediately after a CATCH_LABEL event, before any instructions in the catch/finally block is executed.|Exception object caught by the block|
|                           |JUMP|This event represents a jump instruction in bytecode. |The event itself is not directly recorded in a trace.  The dataId of this event may appear in LABEL events.|
|                           |DEVIDE|This event represents an arithmetic division instruction (IDIV).|The event itself is not directly recorded in a trace.  The dataId of this event may appear in LABEL events.|
|                           |LINE_NUMBER|This event represents an execution of a line of source code.  As a single line of code may be compiled into separated bytecode blocks, a number of LINE_NUMBER events having different data ID may point to the same line number.||


### Runtime Data Contents in the Omniscient mode

The `selogger.reader.LogPrinter` class is to translate the binary format into a text format.

> java -classpath selogger-0.2.3.jar selogger.reader.LogPrinter selogger-output

The command accepts the following options.

 - `-from=N` skips the first N events.
 - `-num=M` terminates the program after printing M events.
 - `-thread=` specifies a list of thread ID separated with commas.  The program ignores events in other threads.
 - `-processparams` links parameter events to its METHOD ENTRY/CALL events.

The output format is like this:

> EventId=475,EventType=CALL,ThreadId=0,DataId=18009,Value=35,objectType=java.lang.String,method:CallType=Regular,Instruction=INVOKEVIRTUAL,Owner=java/lang/String,Name=indexOf,Desc=(Ljava/lang/String;)I,,org/apache/tools/ant/taskdefs/condition/Os:isOs,Os.java:260:38

Each line includes the following attributes.

- Event ID
- Event Type
- Thread ID
- DataID (to refer dataids.txt to its static information)
- Value associated with the event (in case of CALL, it is an object ID)
- objectType is the class name represented by an object ID (if the value points to an object)
- attributes recorded in dataids.txt
- Class Name, 
- File Name, Line Number (recorded as debug symbols)
- Instruction Index of the bytecode in the method (it is useful when you analyze the bytecode with the ASM library)

#### LOG$Types.txt

The file records object types.
It is a CSV file including 6 columns.

- Type ID
- Type Name
- Class file location
- The superclass type ID
- The component type ID (available for an array type) 
- A string representation of class loader that loaded the type and followed by the class name.  This text is linked to the weaving information (`classes.txt`) described below.

#### LOG$ObjectTypesNNNNN.txt

This CSV file includes two columns.
Each row represents an object.

- The first column shows the object ID.
- The second column shows the type ID.

#### LOG$ExceptionNNNNN.txt

This is a semi-structured CSV file records messages and stack traces of exceptions thrown during a program execution.
Each exception is recorded by the following lines.

- Message line including three columns
  - Object ID of the Throwable object
  - A literal "M"
  - Textual message returned by `Throwable.getMessage()`
- Cause Object
  - Object ID of the Throwable object
  - A literal "CS"
  - Object ID of the cause object returned by `Throwable.getCause()`
  - If the object has suppressed exceptions (returned by `getSuppressed()`), their object IDs
- Stack Trace Elements (Each line corresponds to a single line of a stack trace)
  - Object ID of the Throwable object
  - A literal "S"
  - A literal "T" or "F": "T" represents the method is a native method.
  - Class Name
  - Method Name
  - File Name
  - Line Number

#### LOG$StringNNNNN.txt

The file records the contents of string objects used in an execution.
It is a CSV file format; each line has three fields.

- The object ID of the string
- The length of the string
- The content escaped as a JSON string

## Weaving Events

The weaver component generates the following files during the weaving. 
 - `weaving.properties`: The configuration options recognized by the weaver.
 - `classes.txt`: A list of woven classes.  The content is defined in the `selogger.weaver.ClassInfo` class.
 - `methods .txt`: A list of methods in the woven classes.  The content is defined in the `selogger.weaver.MethodInfo` class.
 - `dataids.txt`: A list of Data IDs. 
 - `log.txt`: Recording errors encountered during bytecode manipulation.
 
 
## Limitation

The logging feature for some instructions (in particular, JUMP, RET, INVOKEDYNAMIC instructions) has not been tested well due to the lack of appropriate test cases.

To record Local variable names and line numbers, the tool uses debugging information embedded in class files. 

## Differences from master branch version

The execution trace recorded in this version is incompatible with the master branch version.
The major differences are:
 * Simplified data format
 * Simplified instrumentation implementation
 * Simplified logging implementation (easy to extend)
 * Supported load-time weaving
 * Supported tracing jumps caused by exceptions
 * Supported fixed-size buffer logging
 * Improved reliability with JUnit test cases

The documentation for the master branch is available in the `doc` directory.


