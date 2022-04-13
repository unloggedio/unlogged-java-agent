package com.videobug.agent.logging.perthread.pojo;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

public class StringInfoDocument {

    public static final SimpleAttribute<StringInfoDocument, String> STRING_VALUE =
            new SimpleAttribute<StringInfoDocument, String>("stringValue") {
                public String getValue(StringInfoDocument typeInfoDocument, QueryOptions queryOptions) {
                    return typeInfoDocument.string;
                }
            };


    String string;
    long stringId;

    public StringInfoDocument(long id, String string) {
        this.stringId = id;
        this.string = string;
    }

    public String getString() {
        return string;
    }

    public void setString(String string) {
        this.string = string;
    }

    public long getStringId() {
        return stringId;
    }

    public void setStringId(long stringId) {
        this.stringId = stringId;
    }
}
