package com.videobug.agent.test;

import java.io.File;

import com.videobug.agent.logging.util.FileNameGenerator;
import org.junit.Assert;
import org.junit.Test;

public class SequentialFileNameTest {

	/**
	 * Check the correctness of generated names
	 */
	@Test
	public void testGetNextFile() {
		FileNameGenerator seq = new FileNameGenerator(new File("."), "ABC", ".txt");
		Assert.assertEquals(new File(".", "ABC00001.txt"), seq.getNextFile());
		Assert.assertEquals(new File(".", "ABC00002.txt"), seq.getNextFile());
		Assert.assertEquals(new File(".", "ABC00003.txt"), seq.getNextFile());
		
		FileNameGenerator seq2 = new FileNameGenerator(new File("."), "ABC", ".txt.gz");
		Assert.assertEquals(new File(".", "ABC00001.txt.gz"), seq2.getNextFile());
		Assert.assertEquals(new File(".", "ABC00002.txt.gz"), seq2.getNextFile());
		Assert.assertEquals(new File(".", "ABC00003.txt.gz"), seq2.getNextFile());
	}
}
