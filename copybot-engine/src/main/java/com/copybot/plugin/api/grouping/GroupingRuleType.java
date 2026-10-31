package com.copybot.plugin.api.grouping;

/** The kind of a grouping rule: the {@code type} of a rule written in a pipeline. */
public enum GroupingRuleType {
    /** A main file and the files of the same base name ({@code IMG_1.CR3} + {@code IMG_1.xmp}): {@code sameBase}. */
    SAME_BASE,
    /** A main file and the files named after its full name ({@code IMG_1.CR3} + {@code IMG_1.CR3.xmp}). */
    DOUBLE_EXTENSION,
    /** A main file and the folder named after it ({@code page.html} + {@code page_files/}): {@code folder}. */
    FOLDER,
    /** A main file matched by an expression and the files its companion expression matches: {@code regex}. */
    REGEX
}
