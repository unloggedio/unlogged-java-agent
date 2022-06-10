package com.videobug.agent.logging.util;

import com.videobug.agent.logging.IErrorLogger;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NetworkClient {

    private static String hostname = null;
    private final String serverUrl;
    private final String sessionId;
    private final String token;
    private final IErrorLogger err;

    public NetworkClient(String serverUrl, String sessionId, String token, IErrorLogger err) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.sessionId = sessionId;
        this.err = err;
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

    public void sendPOSTRequest(String url, String attachmentFilePath) throws IOException {

        String charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "insidious/1.0.0");
        headers.put("Authorization", "Bearer " + this.token);

        MultipartUtility form = null;
        try {
            form = new MultipartUtility(url, charset, headers);

            File binaryFile = new File(attachmentFilePath);
            form.addFilePart("file", binaryFile);
            form.addFormField("sessionId", sessionId);
            form.addFormField("hostname", getHostname());

            String response = form.finish();
        } catch (IOException e) {
            err.log("failed to upload - " + e.getMessage());
            throw e;
        }

    }

    public void sendPOSTRequest(String url, String attachmentFilePath, Long threadId) throws IOException {

        String charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "insidious/1.0.0");
        headers.put("Authorization", "Bearer " + this.token);

        MultipartUtility form = new MultipartUtility(url, charset, headers);

        File binaryFile = new File(attachmentFilePath);
        form.addFilePart("file", binaryFile);
        form.addFormField("sessionId", sessionId);
        form.addFormField("hostname", getHostname());
        form.addFormField("threadId", String.valueOf(threadId));


        String response = form.finish();

    }


    public String getServerUrl() {
        return serverUrl;
    }

    public void uploadFile(String filePath) throws IOException {
        System.err.println("File to upload to [" + serverUrl + "]: " + filePath);
        long start = System.currentTimeMillis();
        sendPOSTRequest(serverUrl + "/checkpoint/uploadArchive", filePath);
        long end = System.currentTimeMillis();
        long seconds = (end - start) / 1000;
        if (seconds > 2) {
            System.err.println("Upload took " + seconds + " seconds, deleting file " + filePath);
        }
    }

//    public void uploadFile(String filePath, long threadId) throws IOException {
//        System.err.println("File to upload: " + filePath);
//        long start = System.currentTimeMillis();
//        sendPOSTRequest(serverUrl + "/checkpoint/upload", filePath, threadId);
//        long end = System.currentTimeMillis();
//        long seconds = (end - start) / 1000;
//        if (seconds > 2) {
//            System.err.println("Upload took " + seconds + " seconds, deleting file " + filePath);
//        }
//
//    }

    private void sendPOSTRequest(String url, String attachmentFilePath, Integer threadId) throws IOException {
        String charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "insidious/1.0.0");
        headers.put("Authorization", "Bearer " + this.token);

        MultipartUtility form = new MultipartUtility(url, charset, headers);

        File binaryFile = new File(attachmentFilePath);
        form.addFilePart("file", binaryFile);
        form.addFormField("sessionId", sessionId);
        form.addFormField("hostname", getHostname());
        form.addFormField("threadId", String.valueOf(threadId));
        String response = form.finish();
    }
}
