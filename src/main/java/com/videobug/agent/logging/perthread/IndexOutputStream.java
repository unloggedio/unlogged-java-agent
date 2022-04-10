package com.videobug.agent.logging.perthread;

import java.io.IOException;

public interface IndexOutputStream {
    void writeFileEntry(UploadFile uploadFile) throws IOException;
}
