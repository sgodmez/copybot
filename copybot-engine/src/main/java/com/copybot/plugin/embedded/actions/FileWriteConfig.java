package com.copybot.plugin.embedded.actions;

/**
 * The "actionConfig" of file.write, as written in the pipeline (spec safe-write §1). Every field but
 * outPattern is optional: {@link FileWriteSettings#of} validates the values and applies the defaults.
 *
 * @param overwrite    legacy: true means onConflict.ifDifferent "overwrite", false means "error"
 *                     (ifIdentical "skip" in both cases); refused together with onConflict
 * @param onConflict   what to do when the target already exists
 * @param writeMode    "tempAndRename" (default) or "direct" (with "overwrite", the original is lost as soon
 *                     as the write starts)
 * @param verify       "none", "size" (default) or "readBack"
 * @param deleteSource delete the source once written and verified (default false)
 */
public record FileWriteConfig(
        String outPattern,

        Boolean overwrite,

        OnConflict onConflict,

        String writeMode,

        String verify,

        Boolean deleteSource
) {

    /**
     * @param compare     "size", "sizeAndDate", "partialHash" (default) or "fullHash"
     * @param ifIdentical "skip" (default), "rename", "overwrite" or "error"
     * @param ifDifferent "skip", "rename" (default), "overwrite" or "error"
     */
    public record OnConflict(String compare, String ifIdentical, String ifDifferent) {
    }
}
