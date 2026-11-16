package com.copybot.engine.pipeline;

import java.util.List;

/**
 * The optional {@code archives} block of the pipeline: the unfolding of the archives at the listing. Absent flags are
 * false; {@code include} / {@code exclude} are globs on the path inside the archive (absent: everything).
 */
public record ArchivesConfig(
        Boolean unfold,
        Boolean nested,
        Boolean mergeFolder,
        List<String> include,
        List<String> exclude) {

    public boolean isUnfold() {
        return Boolean.TRUE.equals(unfold);
    }

    public boolean isNested() {
        return Boolean.TRUE.equals(nested);
    }

    public boolean isMergeFolder() {
        return Boolean.TRUE.equals(mergeFolder);
    }
}
