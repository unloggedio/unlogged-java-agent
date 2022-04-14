package com.videobug.agent.logging.perthread;

import com.insidious.common.UploadFile;
import com.videobug.agent.logging.perthread.pojo.ObjectInfoDocument;
import com.videobug.agent.logging.perthread.pojo.StringInfoDocument;
import com.videobug.agent.logging.perthread.pojo.TypeInfoDocument;

import java.io.IOException;
import java.util.List;

public interface IndexOutputStream {
    void writeFileEntry(UploadFile uploadFile) throws IOException;

    void close();

    void indexObjectTypeEntry(long id, int typeId);

    void indexStringEntry(long id, String stringObject);

    void addValueId(long valueId);

    void addProbeId(int probeId);

    void indexTypeEntry(int typeId, String typeName);

    void drainQueueToIndex(
            List<ObjectInfoDocument> objectInfoDocuments,
            List<TypeInfoDocument> typeInfoDocuments,
            List<StringInfoDocument> stringInfoDocuments);

    int fileCount();
}
