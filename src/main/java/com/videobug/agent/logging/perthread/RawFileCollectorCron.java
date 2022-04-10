package com.videobug.agent.logging.perthread;

import com.videobug.agent.logging.IErrorLogger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;

public class RawFileCollectorCron implements Runnable {
    public static final int MAX_CONSECUTIVE_FAILURE_COUNT = 10;
    public static final int FAILURE_SLEEP_DELAY = 10;
    private final IndexOutputStream indexOutputStream;
    private final IErrorLogger errorLogger;
    private final BlockingQueue<UploadFile> fileList;
    public int continuousUploadFailureCount = 0;
    private boolean shutdown = false;
    private boolean skipUploads;

    public RawFileCollectorCron(int continuousUploadFailureCount,
                                IndexOutputStream indexOutputStream,
                                BlockingQueue<UploadFile> fileList,
                                IErrorLogger errorLogger
    ) {
        this.continuousUploadFailureCount = continuousUploadFailureCount;
        this.indexOutputStream = indexOutputStream;
        this.errorLogger = errorLogger;
        this.fileList = fileList;
    }

    public void shutdown() {
        shutdown = true;
    }

    public void upload() {
        long start;
        try {
            long currentTimestamp = System.currentTimeMillis();

            UploadFile logFile = fileList.take();
            File fileToUpload = new File(logFile.path);

            errorLogger.log("Take file [" + logFile.path + "]");

            indexOutputStream.writeFileEntry(logFile);
            fileToUpload.delete();

        } catch (IOException e) {
            System.err.println("Failed to upload file: " + e.getMessage());
            errorLogger.log(e);
            continuousUploadFailureCount++;
            if (continuousUploadFailureCount > MAX_CONSECUTIVE_FAILURE_COUNT) {
                errorLogger.log("continuous " + MAX_CONSECUTIVE_FAILURE_COUNT
                        + " file upload failure, skipping file uploads for 10 mins");
            }
        } catch (InterruptedException e) {
            errorLogger.log("file upload cron interrupted, shutting down");
        }
    }

    @Override
    public void run() {
        while (true) {
            if (shutdown) {
                return;
            }
            long start = System.currentTimeMillis();
            upload();
            long timeToProcessFile = System.currentTimeMillis() - start;
            errorLogger.log("adding file took [" + timeToProcessFile + "] ms");
        }
    }
}