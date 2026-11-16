package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.PatternField;
import com.copybot.plugin.api.config.Required;

/**
 * The "actionConfig" of file.write, as written in the pipeline (spec safe-write §1). Every field but
 * outPattern is optional: {@link FileWriteSettings#of} validates the values and applies the defaults.
 * The annotations describe the fields to the pipeline editor (spec desktop-ui §4); the allowed values
 * and defaults are the ones of {@link FileWriteSettings}.
 *
 * @param onConflict   what to do when the target already exists
 * @param onMissingKey "error" (default), "skip" or "literal": what to do when an expression of outPattern has no value
 * @param writeMode    "tempAndRename" (default) or "direct" (with "overwrite", the original is lost as soon
 *                     as the write starts)
 * @param verify       "none", "size" (default) or "readBack"
 * @param deleteSource delete the source once written and verified (default false)
 */
public record FileWriteConfig(
        @Required @PatternField
        String outPattern,

        @AllowedValues({"error", "skip", "literal"}) @DefaultValue("error")
        String onMissingKey,

        OnConflict onConflict,

        @AllowedValues({"tempAndRename", "direct"}) @DefaultValue("tempAndRename")
        String writeMode,

        @AllowedValues({"none", "size", "readBack"}) @DefaultValue("size")
        String verify,

        @DefaultValue("false")
        Boolean deleteSource
) {

    /**
     * @param compare     "size", "sizeAndDate", "partialHash" (default) or "fullHash"
     * @param ifIdentical "skip" (default), "rename", "overwrite" or "error"
     * @param ifDifferent "skip", "rename" (default), "overwrite" or "error"
     */
    public record OnConflict(
            @AllowedValues({"size", "sizeAndDate", "partialHash", "fullHash"}) @DefaultValue("partialHash")
            String compare,

            @AllowedValues({"skip", "rename", "overwrite", "error"}) @DefaultValue("skip")
            String ifIdentical,

            @AllowedValues({"skip", "rename", "overwrite", "error"}) @DefaultValue("rename")
            String ifDifferent
    ) {
    }
}
