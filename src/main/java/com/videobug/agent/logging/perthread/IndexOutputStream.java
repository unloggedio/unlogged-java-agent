package com.videobug.agent.logging.perthread;

import com.insidious.common.UploadFile;
import com.insidious.common.cqengine.ObjectInfoDocument;
import com.insidious.common.cqengine.StringInfoDocument;
import com.insidious.common.cqengine.TypeInfoDocument;

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
