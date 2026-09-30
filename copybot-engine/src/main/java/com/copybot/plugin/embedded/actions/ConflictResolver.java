package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Decides where an item goes when its target may already exist (spec safe-write §2). */
final class ConflictResolver {

    /**
     * @param target          where to write, or the existing file that makes the item skipped
     * @param replaceExisting the target exists and is overwritten
     * @param skipReason      non null when nothing is written: the reason shown to the user
     * @param identical       the existing target was found identical to the item
     * @param sameFile        the existing target is the source file itself (sort in place): skipped, and
     *                        the source must never be deleted, it is the only copy
     */
    record Decision(Path target, boolean replaceExisting, String skipReason, boolean identical, boolean sameFile) {
        boolean isSkip() {
            return skipReason != null;
        }
    }

    private final FileWriteSettings settings;

    ConflictResolver(FileWriteSettings settings) {
        this.settings = settings;
    }

    /**
     * Free target: write it. Existing target: compared, then ifIdentical or ifDifferent applies; "rename"
     * tries "name (1).ext", "name (2).ext"... each existing candidate being compared in turn, so an
     * identical copy already renamed is found again instead of piling up duplicates.
     * <p>
     * A candidate that is the local source file itself (e.g. file.read on the destination tree, the out
     * pattern giving back the same path) is skipped before any comparison, whatever the policies: a file
     * compared with itself is "identical", and overwriting it or deleting the source would lose the only copy.
     *
     * @throws CopybotException write.conflict.error when the policy is "error"
     */
    Decision resolve(WorkItem item, Path target) throws IOException {
        Path candidate = target;
        for (int n = 1; ; n++) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return new Decision(candidate, false, null, false, false);
            }
            if (isSource(item, candidate)) {
                String reason = ResourcesEngine.getString("write.skip.same-file", candidate);
                return new Decision(candidate, false, reason, true, true);
            }
            boolean identical = FileComparison.identical(item, candidate, settings.compare());
            Policy policy = identical ? settings.ifIdentical() : settings.ifDifferent();
            switch (policy) {
                case SKIP -> {
                    String reason = ResourcesEngine.getString(identical ? "write.skip.identical" : "write.skip.exists", candidate);
                    return new Decision(candidate, false, reason, identical, false);
                }
                case OVERWRITE -> {
                    return new Decision(candidate, true, null, identical, false);
                }
                case ERROR -> throw CopybotException.ofResource("write.conflict.error", candidate);
                case RENAME -> candidate = numbered(target, n);
            }
        }
    }

    /** The existing candidate is the item's own local file (same file key: also a hard link, or another letter case on Windows). */
    private static boolean isSource(WorkItem item, Path candidate) throws IOException {
        Path source = FileComparison.localSource(item);
        return source != null && Files.exists(source, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(source, candidate);
    }

    /** "name (n).ext", or "name (n)" without extension; the leading dot of a hidden file is not an extension. */
    static Path numbered(Path target, int n) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String numbered = dot > 0
                ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                : name + " (" + n + ")";
        return target.resolveSibling(numbered);
    }
}
