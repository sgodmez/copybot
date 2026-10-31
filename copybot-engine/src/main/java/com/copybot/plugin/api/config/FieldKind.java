package com.copybot.plugin.api.config;

/** What a configuration field holds, i.e. which editor control it gets (spec desktop-ui §4). */
public enum FieldKind {
    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    /** a file system path, see {@link FieldHint#DIRECTORY} / {@link FieldHint#FILE} */
    PATH,
    /** one of {@link ConfigField#enumValues()} */
    ENUM,
    /** a nested object, see {@link ConfigField#children()} */
    RECORD,
    /** a JSON array, see {@link ConfigField#elementSchema()} */
    LIST
}
