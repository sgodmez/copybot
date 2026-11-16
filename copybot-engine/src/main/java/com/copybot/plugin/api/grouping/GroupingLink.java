package com.copybot.plugin.api.grouping;

/** How the target name of a regex rule's companion derives from its main file: the {@code link} of the rule. */
public enum GroupingLink {
    /** The full main final name, then the suffix ({@code IMG_1.MP4} -> {@code IMG_1.MP4.json}); the default. */
    NAME,
    /** The main final name without its last extension, then the suffix ({@code C0001.MP4} -> {@code C0001M01.XML}). */
    BASE
}
