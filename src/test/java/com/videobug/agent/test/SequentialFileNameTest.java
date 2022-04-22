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
        File nextFile;
        nextFile = seq.getNextFile();
        Assert.assertTrue(nextFile.getName().startsWith("ABC00001") && nextFile.getName().endsWith(".txt"));
        nextFile = seq.getNextFile();
        Assert.assertTrue(nextFile.getName().startsWith("ABC00002") && nextFile.getName().endsWith(".txt"));
        nextFile = seq.getNextFile();
        Assert.assertTrue(nextFile.getName().startsWith("ABC00003") && nextFile.getName().endsWith(".txt"));

        FileNameGenerator seq2;
        seq2 = new FileNameGenerator(new File("."), "ABC", ".txt.gz");
        nextFile = seq2.getNextFile();
        Assert.assertTrue(nextFile.getName().startsWith("ABC00001") && nextFile.getName().endsWith(".txt.gz"));
        nextFile = seq2.getNextFile();
        Assert.assertTrue(nextFile.getName().startsWith("ABC00002") && nextFile.getName().endsWith(".txt.gz"));
        nextFile = seq2.getNextFile();
        Assert.assertTrue(nextFile.getName().startsWith("ABC00003") && nextFile.getName().endsWith(".txt.gz"));
    }
}
