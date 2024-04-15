package io.unlogged.weaver;

import java.io.IOException;

import io.unlogged.logging.util.TypeIdUtil;
import io.unlogged.weaver.method.JSRInliner;
import io.unlogged.weaver.method.MethodTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.TryCatchBlockSorter;

/**
 * This class weaves logging code into a Java class file.
 * The constructors execute the weaving process.
 */
public class ClassTransformer extends ClassVisitor {

	private final WeaveConfig config;
	private final ClassWriter classWriter;
	private final String PACKAGE_SEPARATOR = "/";
	private String[] interfaces;
	private String superName;
	private String signature;
	private WeaveLog weavingInfo;
	private String fullClassName;
	private String className;
	private String outerClassName;
	private String packageName;
	private String sourceFileName;
	private byte[] weaveResult;
	private String classLoaderIdentifier;

	/**
	 * This constructor weaves the given class and provides the result.
	 *
	 * @param weaver     specifies the state of the weaver.
	 * @param config     specifies the configuration.
	 * @param inputClass specifies a byte array containing the target class.
     */
	public ClassTransformer(WeaveLog weaver, WeaveConfig config, byte[] inputClass, ClassLoader loader) {
		this(weaver, config, new ClassReader(inputClass), loader);
	}

	/**
	 * This constructor weaves the given class and provides the result.
	 *
	 * @param weaver specifies the state of the weaver.
	 * @param config specifies the configuration.
	 * @param reader specifies a class reader to read the target class.
	 */
	public ClassTransformer(WeaveLog weaver, WeaveConfig config, ClassReader reader, ClassLoader loader) {
		// Create a writer for the target class
		this(weaver, config,
				new MetracerClassWriter(reader, loader));
		// Start weaving, and store the result to a byte array
		reader.accept(this, ClassReader.EXPAND_FRAMES);
		weaveResult = classWriter.toByteArray();
		classLoaderIdentifier = TypeIdUtil.getClassLoaderIdentifier(weaver.getFullClassName());

	}

	/**
	 * Initializes the object as a ClassVisitor.
	 * c
	 *
	 * @param weaver specifies the state of the weaver.
	 * @param config specifies the configuration.
	 * @param cw     specifies the class writer (MetracerClassWriter).
	 */
	protected ClassTransformer(WeaveLog weaver, WeaveConfig config, ClassWriter cw) {
		super(Opcodes.ASM9, cw);
		this.weavingInfo = weaver;
		this.config = config;
		this.classWriter = cw;
	}

	@Override
	public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
//		System.err.println("Visit annotation: " + descriptor + " on class: " + className);
		return super.visitAnnotation(descriptor, visible);
	}

	@Override
	public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
//		System.err.println("Visit type annotation: " + typePath);
		return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
	}

	/**
	 * @return the weaving result.
	 */
	public byte[] getWeaveResult() {
		return weaveResult;
	}

	/**
	 * @return the full class name including the package name and class name
	 */
	public String getFullClassName() {
		return fullClassName;
	}

	/**
	 * @return the class name without the package name
	 */
	public String getClassName() {
		return className;
	}

	/**
	 * @return the package name
	 */
	public String getPackageName() {
		return packageName;
	}

	/**
	 * @return the class loader identifier
	 */
	public String getClassLoaderIdentifier() {
		return classLoaderIdentifier;
	}

	/**
	 * A call back from the ClassVisitor.
	 * Record the class information to fields.
	 */
	@Override
	public void visit(int version, int access, String name, String signature,
					  String superName, String[] interfaces) {
//		System.err.println("Visit class ["+ name + "]");
		this.fullClassName = name;
		this.weavingInfo.setFullClassName(fullClassName);
		int index = name.lastIndexOf(PACKAGE_SEPARATOR);
		this.interfaces = interfaces;
		this.superName = superName;
		this.signature = signature;
		if (index >= 0) {
			packageName = name.substring(0, index);
			className = name.substring(index + 1);
		}

		super.visit(version, access, name, signature, superName, interfaces);
	}

	/**
	 * A call back from the ClassVisitor.
	 * Record the source file name.
	 */
	@Override
	public void visitSource(String source, String debug) {
//		System.err.println("Visit source ["+ source + "] + [" + debug + "]");
		super.visitSource(source, debug);
		sourceFileName = source;
	}

	public String getSourceFileName() {
		return sourceFileName;
	}

	/**
	 * A call back from the ClassVisitor.
	 * Record the outer class name if this class is an inner class.
	 */
	@Override
	public void visitInnerClass(String name, String outerName,
								String innerName, int access) {
//		System.err.println("Visit innerClass ["+ name + "] + [" + outerName + "] + [" + innerName + "]");
		super.visitInnerClass(name, outerName, innerName, access);
		if (name.equals(fullClassName)) {
			outerClassName = outerName;
		}
	}

	/**
	 * A call back from the ClassVisitor.
	 * Create an instance of a MethodVisitor that inserts logging code into a method.
	 */
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc,
									 String signature, String[] exceptions) {
		MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
		if (name.equals("equals")
				|| name.equals("hashCode")
				|| name.equals("onNext")
				|| name.equals("onSubscribe")
				|| name.equals("onError")
				|| name.equals("currentContext")
				|| name.equals("onComplete")
		) {
			return mv;
		}
		if (mv != null
		) {
			mv = new TryCatchBlockSorter(mv, access, name, desc, signature, exceptions);
			MethodTransformer trans = new MethodTransformer(
					weavingInfo, config, sourceFileName,
					fullClassName, outerClassName, access,
					name, desc, signature, exceptions, mv
			);
			return new JSRInliner(trans, access, name, desc, signature, exceptions);
		} else {
			return null;
		}
	}

	public String[] getInterfaces() {
		return interfaces;
	}

	public String getSuperName() {
		return superName;
	}

	public String getSignature() {
		return signature;
	}
}
