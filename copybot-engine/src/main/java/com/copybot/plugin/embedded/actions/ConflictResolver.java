package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
     * <p>
     * A directory at a candidate is "different", without comparison: "rename" goes around it, "skip" skips,
     * "error" fails, and "overwrite" fails at the write (a file never replaces a directory).
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
            // a directory is never the item, and is never read (on Windows its size is 0, like an empty file)
            boolean identical = !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    && FileComparison.identical(item, candidate, settings.compare());
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

    /**
     * Where a main item and its COPY companions go (spec grouping-archives, "Conflits : groupe solidaire").
     *
     * @param mainTarget        where to write the main item, or the existing file that makes the group skipped
     * @param companionTargets  the target of each companion, in the order of the companions
     * @param replaceExisting   the existing members are overwritten
     * @param skipReason        non null when the main item is not written: the reason shown to the user
     * @param identical         every existing member was found identical to its item
     * @param sameFile          a member's target is its source file itself: skipped, no source ever deleted
     * @param missingCompanions the indices of the companions absent at their target, to write without replacing
     *                          (also when the group is skipped as identical); empty when the group is skipped
     *                          for another reason
     */
    record GroupDecision(Path mainTarget, List<Path> companionTargets, boolean replaceExisting, String skipReason,
                         boolean identical, boolean sameFile, List<Integer> missingCompanions) {
        boolean isSkip() {
            return skipReason != null;
        }
    }

    /**
     * As {@link #resolve} for a whole group: each candidate of the main item ("name (n).ext") gives the candidate of
     * each companion ({@link Companion#targetName}). All free: written. A member at its own source: skipped. Otherwise
     * the group is identical when every existing member is, and ifIdentical or ifDifferent applies to the whole
     * group; "rename" moves every member to the next candidate. An identical group whose main target is free has its
     * main item written next to the identical companions (nothing to skip there).
     *
     * @throws CopybotException write.conflict.error, naming the member in conflict, when the policy is "error";
     *                          write.group.same-target when two members have the same target
     */
    GroupDecision resolveGroup(WorkItem main, List<Companion> copies, Path target) throws IOException {
        Path mainCandidate = target;
        for (int n = 1; ; n++) {
            String mainName = mainCandidate.getFileName().toString();
            List<Path> companionTargets = new ArrayList<>();
            for (Companion copy : copies) {
                companionTargets.add(mainCandidate.resolveSibling(copy.targetName(mainName)));
            }
            requireDistinctTargets(main, copies, mainCandidate, companionTargets);
            List<Integer> missing = new ArrayList<>();
            Path firstExisting = null;
            Path firstDifferent = null;
            for (int i = -1; i < copies.size(); i++) {
                WorkItem item = i < 0 ? main : copies.get(i).item();
                Path candidate = i < 0 ? mainCandidate : companionTargets.get(i);
                if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    if (i >= 0) {
                        missing.add(i);
                    }
                    continue;
                }
                if (isSource(item, candidate)) {
                    String reason = ResourcesEngine.getString("write.skip.same-file", candidate);
                    return new GroupDecision(mainCandidate, companionTargets, false, reason, true, true, List.of());
                }
                if (firstExisting == null) {
                    firstExisting = candidate;
                }
                if (firstDifferent == null && (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || !FileComparison.identical(item, candidate, settings.compare()))) {
                    firstDifferent = candidate;
                }
            }
            if (firstExisting == null) {
                return new GroupDecision(mainCandidate, companionTargets, false, null, false, false, missing);
            }
            boolean identical = firstDifferent == null;
            Path conflict = identical ? firstExisting : firstDifferent;
            Policy policy = identical ? settings.ifIdentical() : settings.ifDifferent();
            switch (policy) {
                case SKIP -> {
                    if (identical && !Files.exists(mainCandidate, LinkOption.NOFOLLOW_LINKS)) {
                        return new GroupDecision(mainCandidate, companionTargets, false, null, true, false, missing);
                    }
                    String reason = ResourcesEngine.getString(identical ? "write.skip.identical" : "write.skip.exists", conflict);
                    return new GroupDecision(mainCandidate, companionTargets, false, reason, identical, false,
                            identical ? missing : List.of());
                }
                case OVERWRITE -> {
                    return new GroupDecision(mainCandidate, companionTargets, true, null, identical, false, missing);
                }
                case ERROR -> throw CopybotException.ofResource("write.conflict.error", conflict);
                case RENAME -> mainCandidate = numbered(target, n);
            }
        }
    }

    /**
     * Two members of the group with one target, letter case aside (one file on a destination that ignores case):
     * the second would replace the first, then both sources would be deleted. The group fails before any write.
     *
     * @throws CopybotException write.group.same-target, naming both members
     */
    private static void requireDistinctTargets(WorkItem main, List<Companion> copies, Path mainTarget,
                                               List<Path> companionTargets) {
        Map<String, WorkItem> seen = new HashMap<>();
        for (int i = -1; i < copies.size(); i++) {
            WorkItem item = i < 0 ? main : copies.get(i).item();
            Path path = i < 0 ? mainTarget : companionTargets.get(i);
            WorkItem other = seen.putIfAbsent(path.normalize().toString().toLowerCase(Locale.ROOT), item);
            if (other != null) {
                throw CopybotException.ofResource("write.group.same-target", memberName(other), memberName(item), path);
            }
        }
    }

    private static String memberName(WorkItem item) {
        return item.getNameDisplay() != null ? item.getNameDisplay() : item.getSourceLocationDisplay();
    }

    /** The existing candidate is the item's own local file (same file key: also a hard link, or another letter case on Windows). */
    static boolean isSource(WorkItem item, Path candidate) throws IOException {
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
