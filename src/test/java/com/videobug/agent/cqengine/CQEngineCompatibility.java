package com.videobug.agent.cqengine;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.support.serialization.KryoSerializer;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.resultset.ResultSet;
import com.insidious.common.cqengine.TypeInfoDocument;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.equal;

public class CQEngineCompatibility {


    @Test
    public void testCompat() throws FileNotFoundException {

        String indexPath = "events.dat";
        IndexedCollection<TypeInfoDocument> index = new ConcurrentIndexedCollection<>(
                DiskPersistence.onPrimaryKeyInFile(TypeInfoDocument.TYPE_NAME, new File(indexPath))
        );

        index.addIndex(HashIndex.onAttribute(TypeInfoDocument.TYPE_NAME));

        for (int i = 0; i < 100000; i++) {
            TypeInfoDocument e = new TypeInfoDocument();
            e.setTypeName("asd-" + i);
            e.setTypeId(1);
            index.add(e);
        }

        Query<TypeInfoDocument> query = equal(TypeInfoDocument.TYPE_NAME, "asd-8");

        ResultSet<TypeInfoDocument> files = index.retrieve(query);
        assert files.size() == 1;

    }
}
