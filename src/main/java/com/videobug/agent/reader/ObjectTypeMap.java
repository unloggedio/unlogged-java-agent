package com.videobug.agent.reader;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import com.videobug.agent.logging.io.EventStreamLogger;

/**
 * This class is to read Object-Type ID map created by ObjectIdFile class.
 */
public class ObjectTypeMap {

	private static final int LIST_PER_ITEMS = 128 * 1024 * 1024;
	
	public static final String TYPENAME_NOT_AVAILABLE = "N/A";
	
	//private TLongIntHashMap objectTypeMap; 
	private ArrayList<int[]> objectTypes;
	private TypeList typeList;
	long count = 0;
	
	/**
	 * Load files from a specified directory.
	 * @param logfileDir is the directory including object type files.
	 */
	public ObjectTypeMap(File logfileDir) {
		objectTypes = new ArrayList<>(1024);
		objectTypes.add(new int[LIST_PER_ITEMS]);
		register(0, -1); // no type information is available for null
	}
	
	/**
	 * This method records the pair of object ID and type ID loaded from files.
	 * @param objId is an object ID.
	 * @param typeId is a type ID.
	 */
	private void register(long objId, int typeId) {
		assert objId == count: "objId is not sequential. objId=" + Long.toString(objId) + " count=" + Long.toString(count);
		count++;
		int listIndex = (int)(objId / LIST_PER_ITEMS);
		int index = (int)(objId % LIST_PER_ITEMS);
		if (objectTypes.size() == listIndex) {
			objectTypes.add(new int[LIST_PER_ITEMS]);
		}
		objectTypes.get(listIndex)[index] = typeId;
	}
	
	/**
	 * @param objectId an object ID.
	 * @return type ID for the specified object.
	 */
	public int getObjectTypeId(long objectId) {
		int listIndex = (int)(objectId / LIST_PER_ITEMS);
		int index = (int)(objectId % LIST_PER_ITEMS);
		return objectTypes.get(listIndex)[index];
	}

	/**
	 * @param objectId an object ID.
	 * @return type name for the specified object.
	 */
	public String getObjectTypeName(long objectId) {
		int typeId = getObjectTypeId(objectId);
		if (typeList != null) {
			return typeList.getType(typeId);
		} else {
			return TYPENAME_NOT_AVAILABLE;
		}
	}
	
	/**
	 * @param typeId a type ID.
	 * @return type name for the specified type ID.
	 */
	public String getTypeName(int typeId) {
		if (typeList != null) {
			return typeList.getType(typeId);
		} else {
			return TYPENAME_NOT_AVAILABLE;
		}
	}
	
}
