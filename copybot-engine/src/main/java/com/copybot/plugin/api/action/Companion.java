package com.copybot.plugin.api.action;

/**
 * A file that goes with a main item (a sidecar, a linked file), grouped with it at the listing.
 *
 * @param item   the companion file
 * @param role   whether it is copied or only read
 * @param link   how its target name derives from the main item's
 * @param suffix the part added to the main name; may contain {@code /} (a relative path, as the {@code folder} rule)
 */
public record Companion(WorkItem item, CompanionRole role, CompanionLink link, String suffix) {

    /** The target file name of this companion, given the final file name of its main item. */
    public String targetName(String mainTargetFileName) {
        String base = mainTargetFileName;
        if (link == CompanionLink.BASE) {
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
        }
        return base + suffix;
    }
}
