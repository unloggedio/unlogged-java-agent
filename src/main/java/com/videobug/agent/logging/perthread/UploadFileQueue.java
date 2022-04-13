package com.videobug.agent.logging.perthread;

import java.io.IOException;

public interface UploadFileQueue {
    public void add(UploadFile uploadFile) throws IOException;
}
