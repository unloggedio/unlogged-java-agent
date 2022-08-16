package com.videobug.agent.logging.io;

import com.insidious.common.weaver.ClassInfo;
import com.videobug.agent.logging.IEventLogger;
import com.videobug.agent.weaver.WeaveLog;

import java.util.Date;

/**
 * This class is an implementation of IEventLogger that discards all events.
 * This is useful to measure the overhead of inserted logging code without disk writing.
 */
public class DiscardLogger implements IEventLogger {

	/**
	 * Create an instance of the class.
	 * No parameter is needed because the object actually does nothing.
	 */
	public DiscardLogger() {
		// Nothing prepared
	}
	
	/**
	 * The close method does nothing as no resource is used by this logger.
	 */
	@Override
	public void close() {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, boolean value) {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, byte value) {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, char value) {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, double value) {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, float value) {
	}

	@Override
	public void recordWeaveInfo(byte[] byteArray, ClassInfo classIdEntry, WeaveLog log) {

	}

	@Override
	public void registerClass(Integer id, Class<?> type) {

	}

	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, int value) {
	}

	@Override
	public void recordEvent(int dataId, Integer value) {

	}

	@Override
	public void recordEvent(int dataId, Long value) {

	}

	@Override
	public void recordEvent(int dataId, Short value) {

	}

	@Override
	public void recordEvent(int dataId, Boolean value) {

	}

	@Override
	public void recordEvent(int dataId, Double value) {

	}

	@Override
	public void recordEvent(int dataId, Float value) {

	}

	@Override
	public void recordEvent(int dataId, Byte value) {

	}

	@Override
	public void recordEvent(int dataId, Date value) {

	}

	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, long value) {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, Object value) {
	}
	
	/**
	 * This logger does not record the given value.
	 */
	@Override
	public void recordEvent(int dataId, short value) {
	}
}
