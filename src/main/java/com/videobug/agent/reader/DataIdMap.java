package com.videobug.agent.reader;

import java.io.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Scanner;

import com.insidious.common.parser.KaitaiInsidiousClassWeaveParser;
import com.insidious.common.weaver.DataInfo;
import com.insidious.common.weaver.LogLevel;
import com.insidious.common.weaver.MethodInfo;
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
		classes = new ArrayList<>(1024 * 128);
		methods = new ArrayList<>(1024 * 1024);
		dataIds = new ArrayList<>(4 * 1024 * 1024);
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
}
