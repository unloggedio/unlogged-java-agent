package com.insidious.common.parser;

import io.kaitai.struct.ByteBufferKaitaiStream;
import io.kaitai.struct.KaitaiStream;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class KaitaiInsidiousClassWeaveParserTest {

    @Test
    public void fromFile() throws IOException {

        InputStream zis = this.getClass().getClassLoader().getResourceAsStream("class.weave.dat.zip");
        ZipInputStream zipFile = new ZipInputStream(zis);
        ZipEntry entry = zipFile.getNextEntry();

        // Buffer size taken to be 1000 say.
        byte[] buffer = new byte[1000];

        // Creating an object of ByteArrayOutputStream class
        ByteArrayOutputStream byteArrayOutputStream
                = new ByteArrayOutputStream();

        // Try block to check for exceptions
        try {
            int temp;

            while ((temp = zipFile.read(buffer))
                    != -1) {
                byteArrayOutputStream.write(buffer, 0,
                        temp);
            }
        }

        // Catch block to handle the exceptions
        catch (IOException e) {

            // Display the exception/s on the console
            System.out.println(e);
        }

        // Mow converting byte array output stream to byte
        // array
        byte[] byteArray
                = byteArrayOutputStream.toByteArray();

        KaitaiStream fileStream = new ByteBufferKaitaiStream(byteArray);
        KaitaiInsidiousClassWeaveParser classWeave =
                new KaitaiInsidiousClassWeaveParser(fileStream);
        assert classWeave.classInfo().size() != 0;
    }
}