package com.copybot.engine.resume;

import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * "Resume from destination": an item counts as imported when its target directory exists (one
 * directory per day: deleting photos inside it afterwards does not move the resume point back).
 * The import stopped after the last item found at the destination, whatever was deleted there before it: the items
 * are gone through from the last one until one is found. Every item after it is to copy, hence analysed anyway: only
 * the targets of the last batch gone through that are before the point are resolved for nothing (resolving one may
 * need its analysis).
 */
public final class DestinationProbe {

    public record Candidate(ItemKey key, Path targetDir) {
    }

    /**
     * @param warning message to show the user, null when none
     * @param toCheck the point follows a missing target ({@code resume.warn.isolated-point}): it must not become the
     *                cursor, the next run probes the destination again
     */
    public record Result(ResumePoint point, String warning, boolean toCheck) {
        public Result(ResumePoint point, String warning) {
            this(point, warning, false);
        }
    }

    /** The target directory of the item at this index, resolved on demand. */
    @FunctionalInterface
    public interface Targets {
        /** @return empty when this item cannot be probed (it stays selected) */
        Optional<Path> dirOf(int index) throws InterruptedException;

        /** Resolves the targets of these items at once (e.g. analyses them in parallel), before {@link #dirOf}. */
        default void resolve(List<Integer> indices) throws InterruptedException {
        }
    }

    /**
     * The first batch of items gone through from the end, then each batch twice as large up to {@link #MAX_BATCH};
     * also the number of items a round of the search for the target before the point resolves.
     */
    private static final int FIRST_BATCH = 16;
    /**
     * At most this many items before the point have their target resolved by the search from the end; the search for
     * the target before the point adds ~log2(n) + 16 log16(n) (by batches too).
     */
    private static final int MAX_BATCH = 64;

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
     * meaningless), then goes through the items from the last one, by batches, until one is found at the destination;
     * an item that cannot be probed is passed.
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
        return probe(ordered, targets, dirExists, singleDirectoryGuard, i -> true);
    }

    /**
     * @param guarded the items whose first and last targets the single-directory guard compares: the ones following the
     *                pipeline pattern, a file with its own out pattern tells nothing about it (spec own-out-pattern §2)
     */
    public static Result probe(List<ItemKey> ordered, Targets targets, Predicate<Path> dirExists,
                               boolean singleDirectoryGuard, IntPredicate guarded) throws InterruptedException {
        Map<Integer, Optional<Path>> resolved = new HashMap<>();
        Set<Integer> known = new HashSet<>(); // the items resolved by a batch, or one by one (in resolved)
        Targets cached = i -> {
            Optional<Path> dir = resolved.get(i);
            if (dir == null) {
                dir = targets.dirOf(i);
                resolved.put(i, dir);
                known.add(i);
            }
            return dir;
        };
        int first = nextProbeable(cached, 0, ordered.size());
        if (first < 0) {
            return new Result(ResumePoint.all(), null);
        }
        int last = previousProbeable(cached, ordered.size() - 1, first);
        if (singleDirectoryGuard) {
            Targets guardedTargets = i -> guarded.test(i) ? cached.dirOf(i) : Optional.empty();
            int guardedFirst = nextProbeable(guardedTargets, first, last + 1);
            int guardedLast = guardedFirst < 0 ? -1 : previousProbeable(guardedTargets, last, guardedFirst);
            if (guardedLast != guardedFirst) {
                Path firstDir = cached.dirOf(guardedFirst).orElseThrow();
                if (firstDir.equals(cached.dirOf(guardedLast).orElseThrow())) {
                    // the out pattern has no variable directory: existence of the only directory tells nothing
                    return new Result(ResumePoint.all(), ResourcesEngine.getString("resume.warn.single-directory", firstDir));
                }
            }
        }
        Map<Path, Boolean> existing = new HashMap<>(); // a directory is checked once for all its items
        Predicate<Path> exists = dir -> existing.computeIfAbsent(dir, dirExists::test);
        int found = -1;
        int batch = FIRST_BATCH;
        for (int end = last + 1; end > first && found < 0; end -= batch, batch = Math.min(2 * batch, MAX_BATCH)) {
            int from = Math.max(first, end - batch);
            List<Integer> indices = new ArrayList<>();
            for (int i = end - 1; i >= from; i--) {
                indices.add(i);
            }
            targets.resolve(indices);
            known.addAll(indices);
            for (int i = end - 1; i >= from && found < 0; i--) {
                Optional<Path> dir = cached.dirOf(i);
                if (dir.isPresent() && exists.test(dir.get())) {
                    found = i;
                }
            }
        }
        if (found < 0) {
            return new Result(ResumePoint.all(), null);
        }
        ItemKey point = ordered.get(found);
        if (!isolated(targets, cached, known, exists, found)) {
            return new Result(ResumePoint.after(point), null);
        }
        return new Result(ResumePoint.after(point), ResourcesEngine.getString("resume.warn.isolated-point",
                point.name(), ResumeResolver.displayDate(point.date())), true);
    }

    /**
     * True when the previous target, the one of the last item before the point with another target, is missing:
     * deleted at the destination, or the point comes from elsewhere (another source writing in the same directories, a
     * file dated in the future) and skips what is not imported yet. Looks back by doubling steps down to the first
     * item, then narrows the interval between the last item with another target and the first with the point's one
     * (the items of a target follow each other) by {@link #FIRST_BATCH} items a round; each round is one batch.
     *
     * @param targets  resolves the batches
     * @param cached   the targets, each one resolved once
     * @param known    the items already resolved
     */
    private static boolean isolated(Targets targets, Targets cached, Set<Integer> known,
                                    Predicate<Path> exists, int found)
            throws InterruptedException {
        Path target = cached.dirOf(found).orElseThrow();
        List<Integer> back = new ArrayList<>();
        for (int step = 1; found > 0 && (back.isEmpty() || back.getLast() > 0); step *= 2) {
            back.add(Math.max(found - step, 0));
        }
        // the items of the last batch gone through (the first steps back) tell it most of the time: nothing more is
        // resolved then
        int knownSteps = 0;
        while (knownSteps < back.size() && known.contains(back.get(knownSteps))) {
            knownSteps++;
        }
        List<Integer> steps = back.subList(0, knownSteps);
        int other = narrow(cached, cached, target, steps, -1);
        if (other < 0) {
            steps = back;
            other = narrow(targets, cached, target, steps, -1);
        }
        if (other < 0) {
            return false; // the point is in the first target (or nothing before it can be probed)
        }
        int same = nearestSame(cached, steps, other, found);
        while (same - other > 1) {
            int gap = same - other;
            int count = Math.min(gap - 1, FIRST_BATCH);
            List<Integer> probes = new ArrayList<>();
            for (int k = count; k >= 1; k--) {
                probes.add(other + (int) ((long) gap * k / (count + 1)));
            }
            int nearer = narrow(targets, cached, target, probes, other);
            int nearerSame = nearestSame(cached, probes, nearer, same);
            if (nearer == other && nearerSame == same) {
                // no probe can be probed: the items between them, one by one (they mostly cannot be probed either)
                int probed = previousProbeable(cached, same - 1, other + 1);
                if (probed < 0) {
                    break;
                }
                if (cached.dirOf(probed).orElseThrow().equals(target)) {
                    nearerSame = probed;
                } else {
                    nearer = probed;
                }
            }
            other = nearer;
            same = nearerSame;
        }
        return !exists.test(cached.dirOf(other).orElseThrow());
    }

    /** The first of these items after {@code other} that can be probed (they have the point's target), else {@code same}. */
    private static int nearestSame(Targets cached, List<Integer> items, int other, int same) throws InterruptedException {
        int nearest = same;
        for (int i : items) {
            if (i > other && i < nearest && cached.dirOf(i).isPresent()) {
                nearest = i;
            }
        }
        return nearest;
    }

    /**
     * Resolves these items (sorted from the last) as one batch.
     *
     * @return the first of them with a target other than this one, {@code other} when none (all with this target, or
     * none that can be probed)
     */
    private static int narrow(Targets targets, Targets cached, Path target, List<Integer> descending, int other)
            throws InterruptedException {
        targets.resolve(descending);
        for (int i : descending) {
            Optional<Path> dir = cached.dirOf(i);
            if (dir.isPresent() && !dir.get().equals(target)) {
                return i;
            }
        }
        return other;
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
