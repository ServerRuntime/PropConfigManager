package com.flexcity.configmanager.model;

/**
 * Tek bir properties satırını temsil eder.
 */
public class PropertyEntry {

    public enum Type { PROPERTY, COMMENT, BLANK }

    private Type type;
    private String key;
    private String value;
    private String rawLine;   // orijinal satır (yorum/boş satırlar için)

    public PropertyEntry() {}

    public static PropertyEntry property(String key, String value) {
        PropertyEntry e = new PropertyEntry();
        e.type  = Type.PROPERTY;
        e.key   = key;
        e.value = value;
        return e;
    }

    public static PropertyEntry comment(String rawLine) {
        PropertyEntry e = new PropertyEntry();
        e.type    = Type.COMMENT;
        e.rawLine = rawLine;
        return e;
    }

    public static PropertyEntry blank() {
        PropertyEntry e = new PropertyEntry();
        e.type    = Type.BLANK;
        e.rawLine = "";
        return e;
    }

    public Type getType()             { return type; }
    public void setType(Type type)    { this.type = type; }

    public String getKey()            { return key; }
    public void setKey(String key)    { this.key = key; }

    public String getValue()              { return value; }
    public void setValue(String value)    { this.value = value; }

    public String getRawLine()            { return rawLine; }
    public void setRawLine(String rawLine){ this.rawLine = rawLine; }
}
