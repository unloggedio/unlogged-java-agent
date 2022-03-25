package com.insidious.agent.logging.util;

import com.insidious.agent.logging.IErrorLogger;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NetworkClient {

    private final static String CRLF = "\r\n"; // Line separator required by multipart/form-data.
    private static String hostname = null;
    private static String boundary;
    private final String serverUrl;
    private final String sessionId;
    private final String token;
    private final IErrorLogger err;
    private final char[] fixedPostMetadata;

    public NetworkClient(String serverUrl, String sessionId, String token, IErrorLogger err) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.sessionId = sessionId;
        this.err = err;


        fixedPostMetadata = preparePostMetadata(sessionId);


    }

    public static String getHostname() {
        if (hostname != null) {
            return hostname;
        }


        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream())
                );
                hostname = reader.readLine();
                reader.close();
            } catch (IOException ex) {
                String userName = System.getProperty("user.name");
                if (userName == null) {
                    userName = "n/a";
                }
                hostname = userName + "-" + UUID.randomUUID();
            }
        }
        System.out.println("videobug session hostname is [" + hostname + "]");

        return hostname;
    }

    private static char[] preparePostMetadata(String sessionId) {

        CharArrayWriter charWriter = new CharArrayWriter();
        PrintWriter writer = new PrintWriter(charWriter);


        // Just generate some unique random value.
        boundary = "------------------------" + Long.toHexString(System.currentTimeMillis());


        // start entry

        writer.append("--").append(boundary).append(CRLF);
        writer.append("Content-Disposition: form-data; name=\"sessionId\"").append(CRLF).append(CRLF);
        writer.append(sessionId).append(CRLF); // + URLConnection.guessContentTypeFromName(binaryFile.getName())).append(CRLF);
        // end entry


        // start entry
        writer.append("--").append(boundary).append(CRLF);
        writer.append("Content-Disposition: form-data; name=\"hostname\"").append(CRLF).append(CRLF);
        writer.append(getHostname()).write(CRLF); // + URLConnection.guessContentTypeFromName(binaryFile.getName())).append(CRLF);


        // end
        writer.append("--").append(boundary).append("--").flush();

//        writer.flush();
        return charWriter.toCharArray();
    }

    public void sendPOSTRequest(String url, String attachmentFilePath) throws IOException {

        String charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "insidious/1.0.0");
        headers.put("Authorization", "Bearer " + this.token);

        MultipartUtility form = new MultipartUtility(url, charset, headers);

        File binaryFile = new File(attachmentFilePath);
        form.addFilePart("file", binaryFile);
        form.addFormField("sessionId", sessionId);
        form.addFormField("hostname", getHostname());


        String response = form.finish();

    }


    public String getServerUrl() {
        return serverUrl;
    }

    public void uploadFile(String filePath) throws IOException {
        System.err.println("File to upload: " + filePath);
        long start = System.currentTimeMillis();
        sendPOSTRequest(serverUrl + "/checkpoint/upload", filePath);
        long end = System.currentTimeMillis();
        long seconds = (end - start) / 1000;
        if (seconds > 2) {
            System.err.println("Upload took " + seconds + " seconds, deleting file " + filePath);
        }

    }
}
