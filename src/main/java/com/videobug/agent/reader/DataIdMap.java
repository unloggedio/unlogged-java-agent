package com.videobug.agent.reader;

import java.io.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Scanner;

import com.insidious.common.parser.KaitaiInsidiousClassWeaveParser;
import com.insidious.common.weaver.LogLevel;
import com.insidious.common.weaver.MethodInfo;
import com.videobug.agent.weaver.DataInfo;
import com.videobug.agent.weaver.Weaver;
import io.kaitai.struct.ByteBufferKaitaiStream;

/**
 * This class is to access class/method/data ID files created by the weaver.
 */
public class DataIdMap {

	private static final String SEPARATOR = ",";
	private ArrayList<KaitaiInsidiousClassWeaveParser.ClassInfo> classes;
	private ArrayList<MethodInfo> methods;
	private ArrayList<DataInfo> dataIds;
	private ObjectTypeMap objects;

	/**
	 * Create an instance by loading files from the specified directory.
	 * @param dir is a directory including the weaver result files.
	 * @throws IOException
	 */
	public DataIdMap(File dir) throws IOException {
		classes = new ArrayList<>(1024);
		methods = new ArrayList<>(1024 * 1024);
		dataIds = new ArrayList<>(4 * 1024 * 1024);
		loadClassEntryFile(dir);
		loadMethodEntryFile(dir);
		loadDataIdEntryFile(dir);
		
		objects = new ObjectTypeMap(dir);
		
	}

	/**
	 * Get the information of a class corresponding to a given classId.
	 */
	public KaitaiInsidiousClassWeaveParser.ClassInfo getClassEntry(int classId) {
		return classes.get(classId);
	}

	/**
	 * Get the information of a method corresponding to a given classId.
	 */
	public MethodInfo getMethod(int methodId) {
		return methods.get(methodId);
	}

	/**
	 * Get the information of a data ID corresponding to a given classId.
	 */
	public DataInfo getDataId(int dataId) {
		return dataIds.get(dataId);
	}
	
	/**
	 * Get the object type of an object corresponding to a given object Id.
	 */
	public String getObjectType(long objectId) {
		return objects.getObjectTypeName(objectId);
	}
	
	/**
	 * Load ClassInfo objects from a file in a specified directory.
	 * @param dir specifies a directory.
	 */
	private void loadClassEntryFile(File dir) throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(new File(dir, Weaver.CLASS_ID_FILE)));
		for (String line = reader.readLine(); line != null; line = reader.readLine()) {
			classes.add(parse(line));
		}
		reader.close();

		for (int i=0; i<classes.size(); i++) {
			assert classes.get(i).classId() == i: "Index must be consistent with Class ID.";
		}
	}


	/**
	 * Create an instance from a string representation created by
	 * ClassInfo.toString.
	 *
	 * @param s is the string representation.
	 * @return an instance.
	 */
	public static KaitaiInsidiousClassWeaveParser.ClassInfo parse(String s) {
		Scanner sc = new Scanner(s);
		sc.useDelimiter(SEPARATOR);
		int classId = sc.nextInt();
		String container = sc.next();
		String filename = sc.next();
		String className = sc.next();
		LogLevel level = LogLevel.valueOf(sc.next());
		String hash = sc.next();
		String id = sc.next();
		sc.close();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		KaitaiInsidiousClassWeaveParser.ClassInfo classInfo
				= new KaitaiInsidiousClassWeaveParser.ClassInfo(new ByteBufferKaitaiStream(baos.toByteArray()));
		return classInfo;
	}


	/**
	 * Load MethodInfo objects from a file in a specified directory.
	 * @param dir specifies a directory.
	 */
	private void loadMethodEntryFile(File dir) throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(new File(dir, Weaver.METHOD_ID_FILE)));
		for (String line = reader.readLine(); line != null; line = reader.readLine()) {
			methods.add(MethodInfo.parse(line));
		}
		reader.close();

		for (int i=0; i<methods.size(); i++) {
			MethodInfo m = methods.get(i);
			assert m.getMethodId() == i: "Index must be consistent with Class ID.";
			assert m.getClassName().equals(classes.get(m.getClassId()).className()): "MethodEntry must be consistent with ClassEntry";
		}
	}

	/**
	 * Load DataInfo objects from a file in a specified directory.
	 * @param dir specifies a directory.
	 */
	private void loadDataIdEntryFile(File dir) throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(new File(dir, Weaver.DATA_ID_FILE)));
		for (String line = reader.readLine(); line != null; line = reader.readLine()) {
			dataIds.add(DataInfo.parse(line));
		}
		reader.close();
	}
	
}
