package com.copybot.plugin.api.action;

/** How the target name of a companion derives from the final name of its main item. */
public enum CompanionLink {
    /** The main final name without its last extension, then the suffix ({@code IMG_1.CR3} -> {@code IMG_1.xmp}). */
    BASE,
    /** The full main final name, then the suffix ({@code IMG_1.CR3} -> {@code IMG_1.CR3.xmp}). */
    NAME
}
