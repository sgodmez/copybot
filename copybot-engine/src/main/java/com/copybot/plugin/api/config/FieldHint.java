package com.copybot.plugin.api.config;

/** How the editor helps to fill a field, from its annotations. */
public enum FieldHint {
    /** {@link DirectoryPath}: a directory chooser */
    DIRECTORY,
    /** {@link FilePath}: a file chooser */
    FILE,
    /** {@link PatternField}: the known pattern variables are listed */
    PATTERN,
    /** {@link Secret}: a choice of secret instead of a typed value */
    SECRET
}
