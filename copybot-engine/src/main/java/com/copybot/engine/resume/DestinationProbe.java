package com.copybot.engine.resume;

import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * "Resume from destination": an item counts as imported when its target directory exists (one
 * directory per day: deleting photos inside it afterwards does not move the resume point back).
 * The destination is assumed filled up to a point, so a dichotomy finds it in ~log2(n) checks, and only
 * the targets of the items it probes are resolved (resolving one may need its analysis).
 */
public final class DestinationProbe {

    public record Candidate(ItemKey key, Path targetDir) {
    }

    /** @param warning message to show the user, null when none */
    public record Result(ResumePoint point, String warning) {
    }

    /** The target directory of the item at this index, resolved on demand. */
    @FunctionalInterface
    public interface Targets {
        /** @return empty when this item cannot be probed (it is left to the dichotomy around it) */
        Optional<Path> dirOf(int index) throws InterruptedException;
    }

    private DestinationProbe() {
    }

    /** @param ordered candidates sorted by key, their target directories already known */
    public static Result probe(List<Candidate> ordered, Predicate<Path> dirExists) {
        try {
            return probe(ordered.stream().map(Candidate::key).toList(),
                    i -> Optional.of(ordered.get(i).targetDir()), dirExists);
        } catch (InterruptedException e) {
            throw new IllegalStateException("unreachable: the targets are known", e);
        }
    }

    /**
     * Resolves the first and the last target (an out pattern without variable directory makes the probe
     * meaningless), then those of the items the dichotomy checks; an item that cannot be probed is replaced by
     * the nearest one that can, within the interval still searched.
     *
     * @param ordered the keys of the items, sorted
     */
    public static Result probe(List<ItemKey> ordered, Targets targets, Predicate<Path> dirExists) throws InterruptedException {
        return probe(ordered, targets, dirExists, true);
    }

    /**
     * @param singleDirectoryGuard false when the targets are the files themselves ({@code destinationMatch: file},
     *                             spec execution-mode §2): a fixed directory is fine, each file is checked
     */
    public static Result probe(List<ItemKey> ordered, Targets targets, Predicate<Path> dirExists,
                               boolean singleDirectoryGuard) throws InterruptedException {
        Map<Integer, Optional<Path>> resolved = new HashMap<>();
        Targets cached = i -> {
            Optional<Path> dir = resolved.get(i);
            if (dir == null) {
                dir = targets.dirOf(i);
                resolved.put(i, dir);
            }
            return dir;
        };
        int first = nextProbeable(cached, 0, ordered.size());
        if (first < 0) {
            return new Result(ResumePoint.all(), null);
        }
        int last = previousProbeable(cached, ordered.size() - 1, first);
        Path firstDir = cached.dirOf(first).orElseThrow();
        if (singleDirectoryGuard && last != first && firstDir.equals(cached.dirOf(last).orElseThrow())) {
            // the out pattern has no variable directory: existence of the only directory tells nothing
            return new Result(ResumePoint.all(), ResourcesEngine.getString("resume.warn.single-directory", firstDir));
        }
        int lastExisting = -1;
        int firstMissing = ordered.size();
        while (firstMissing - lastExisting > 1) {
            int mid = (lastExisting + firstMissing) >>> 1;
            int probed = nextProbeable(cached, mid, firstMissing);
            if (probed < 0) {
                probed = previousProbeable(cached, mid - 1, lastExisting + 1);
            }
            if (probed < 0) {
                break; // nothing left to probe between the last existing and the first missing
            }
            if (dirExists.test(cached.dirOf(probed).orElseThrow())) {
                lastExisting = probed;
            } else {
                firstMissing = probed;
            }
        }
        return new Result(lastExisting < 0 ? ResumePoint.all() : ResumePoint.after(ordered.get(lastExisting)), null);
    }

    /** The first probeable index in [from, to), -1 when none. */
    private static int nextProbeable(Targets targets, int from, int to) throws InterruptedException {
        for (int i = from; i < to; i++) {
            if (targets.dirOf(i).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    /** The last probeable index in [downTo, from], -1 when none. */
    private static int previousProbeable(Targets targets, int from, int downTo) throws InterruptedException {
        for (int i = from; i >= downTo; i--) {
            if (targets.dirOf(i).isPresent()) {
                return i;
            }
        }
        return -1;
    }
}
