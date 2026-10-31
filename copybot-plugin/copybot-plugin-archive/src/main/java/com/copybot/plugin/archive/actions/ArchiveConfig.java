package com.copybot.plugin.archive.actions;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.Required;

/**
 * The "actionConfig" of archive.zip.
 *
 * @param target         the file to write; {date} (yyyy-MM-dd), {time} (HHmmss) and {pipeline} are replaced
 * @param content        zip (the files and a SHA256SUMS manifest) or manifest (SHA256SUMS alone); zip when absent
 * @param includeSkipped also archive the targets of the skipped items (false when absent)
 * @param baseFolder     the folder the paths of the entries are relative to; absent or blank: the common directory
 *                       of the files
 */
public record ArchiveConfig(
        @Required
        String target,

        @AllowedValues({"zip", "manifest"}) @DefaultValue("zip")
        String content,

        @DefaultValue("false")
        Boolean includeSkipped,

        String baseFolder
) {
}
