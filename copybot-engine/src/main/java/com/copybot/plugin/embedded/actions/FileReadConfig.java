package com.copybot.plugin.embedded.actions;

import java.util.List;

/**
 * The "actionConfig" of file.read (spec safe-write §7).
 *
 * @param recursive     the whole tree (default), or the first level only when false
 * @param include       globs relative to path, case-insensitive; absent or empty: every file
 * @param exclude       globs relative to path, case-insensitive; they win over include
 * @param includeHidden hidden files and directories are skipped unless true
 */
public record FileReadConfig(
        String path,

        Boolean recursive,

        List<String> include,

        List<String> exclude,

        Boolean includeHidden
) {

    public boolean isRecursive() {
        return recursive == null || recursive;
    }

    public boolean isIncludeHidden() {
        return Boolean.TRUE.equals(includeHidden);
    }
}
