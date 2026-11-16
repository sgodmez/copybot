package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;

/**
 * What a remote output does when its target exists (spec remote §5). No content is compared on a server: the size,
 * and the date within 2 seconds with "sizeAndDate".
 *
 * @param compare     "size" (default) or "sizeAndDate"
 * @param ifIdentical "skip" (default), "rename", "overwrite" or "error"
 * @param ifDifferent "skip", "rename" (default), "overwrite" or "error"
 */
public record RemoteOnConflict(
        @AllowedValues({"size", "sizeAndDate"}) @DefaultValue("size")
        String compare,

        @AllowedValues({"skip", "rename", "overwrite", "error"}) @DefaultValue("skip")
        String ifIdentical,

        @AllowedValues({"skip", "rename", "overwrite", "error"}) @DefaultValue("rename")
        String ifDifferent
) {
}
