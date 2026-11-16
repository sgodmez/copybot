package com.copybot.engine.resume;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

public class DestinationProbeTest {

    private static final Path D1 = Path.of("nas", "2026-09-01");
    private static final Path D2 = Path.of("nas", "2026-09-02");
    private static final Path D3 = Path.of("nas", "2026-09-03");

    private static DestinationProbe.Candidate c(int day, int n, Path dir) {
        return new DestinationProbe.Candidate(
                new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:%02dZ", day, n)), "IMG_" + day + n), dir);
    }

    private static final List<DestinationProbe.Candidate> CARD = List.of(
            c(1, 1, D1), c(1, 2, D1), c(2, 1, D2), c(2, 2, D2), c(3, 1, D3));

    @Test
    public void nothingImportedYetSelectsEverything() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, dir -> false);
        assertEquals(ResumePoint.all(), r.point());
        assertNull(r.warning());
    }

    @Test
    public void resumesAfterTheLastItemWhoseDirectoryExists() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, Set.of(D1, D2)::contains);
        assertEquals(ResumePoint.after(CARD.get(3).key()), r.point(),
                "files of an existing day directory count as imported, even if some were deleted from it");
    }

    @Test
    public void onlyTheFirstDirectoryExists() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, Set.of(D1)::contains);
        assertEquals(ResumePoint.after(CARD.get(1).key()), r.point());
    }

    @Test
    public void everythingImported() {
        DestinationProbe.Result r = DestinationProbe.probe(CARD, dir -> true);
        assertEquals(ResumePoint.after(CARD.get(4).key()), r.point());
    }

    @Test
    public void checksEachDirectoryOnce() {
        List<DestinationProbe.Candidate> big = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            big.add(new DestinationProbe.Candidate(
                    new ItemKey(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L), "F" + i),
                    Path.of("nas", "d" + (i / 10))));
        }
        Set<Path> checked = new HashSet<>();
        AtomicInteger checks = new AtomicInteger();
        DestinationProbe.Result r = DestinationProbe.probe(big, dir -> {
            checks.incrementAndGet();
            checked.add(dir);
            return Integer.parseInt(dir.getFileName().toString().substring(1)) < 42;
        });
        assertEquals(ResumePoint.after(big.get(419).key()), r.point(), "the last item of the last existing directory d41");
        assertEquals(checked.size(), checks.get(), "one check per directory");
        assertEquals(60, checks.get(), "d99 down to d41, then d40 before the point");
    }

    @Test
    public void singleTargetDirectoryIsIgnoredWithAWarning() {
        List<DestinationProbe.Candidate> flat = List.of(c(1, 1, D1), c(2, 1, D1), c(3, 1, D1));
        DestinationProbe.Result r = DestinationProbe.probe(flat, dir -> true);
        assertEquals(ResumePoint.all(), r.point());
        assertNotNull(r.warning());
    }

    /** 1000 hourly files, 10 per day directory d0..d99, directories up to d41 imported. */
    private static List<ItemKey> hourly() {
        List<ItemKey> keys = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(new ItemKey(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L), "F" + i));
        }
        return keys;
    }

    private static boolean imported(Path dir) {
        return Integer.parseInt(dir.getFileName().toString().substring(1)) < 42;
    }

    @Test
    public void resolvesTheTargetsAfterThePointAndOneBatchBefore() throws InterruptedException {
        List<ItemKey> keys = hourly();
        Set<Integer> resolved = new HashSet<>();
        DestinationProbe.Result r = DestinationProbe.probe(keys, i -> {
            resolved.add(i);
            return Optional.of(Path.of("nas", "d" + (i / 10)));
        }, DestinationProbeTest::imported);

        assertEquals(ResumePoint.after(keys.get(419)), r.point());
        assertTrue(resolved.containsAll(Set.of(0, 999)), "the first and the last tell whether the directory varies");
        long before = resolved.stream().filter(i -> i <= 419).count();
        assertTrue(before <= 64 + 1, "the first, then at most a batch before the point, got " + before);
    }

    @Test
    public void resolvesByBatchesFromTheEnd() throws InterruptedException {
        List<ItemKey> keys = hourly();
        List<String> batches = new ArrayList<>();
        DestinationProbe.Targets targets = new DestinationProbe.Targets() {
            @Override
            public Optional<Path> dirOf(int index) {
                return Optional.of(Path.of("nas", "d" + (index / 10)));
            }

            @Override
            public void resolve(List<Integer> indices) {
                if (batches.size() < 11) { // the batches from the end, then the look for the day before the point
                    batches.add(indices.getLast() + ".." + (indices.getFirst() + 1));
                }
            }
        };

        DestinationProbe.probe(keys, targets, DestinationProbeTest::imported);

        assertEquals(List.of("984..1000", "952..984", "888..952", "824..888", "760..824", "696..760", "632..696",
                "568..632", "504..568", "440..504", "376..440"), batches, "16, 32, then 64 at most");
    }

    @Test
    public void anUnresolvableFileIsSkippedByTheSearchFromTheEnd() throws InterruptedException {
        List<ItemKey> keys = hourly();
        DestinationProbe.Result r = DestinationProbe.probe(keys,
                i -> i % 3 == 0 ? Optional.empty() : Optional.of(Path.of("nas", "d" + (i / 10))),
                DestinationProbeTest::imported);

        assertEquals(ResumePoint.after(keys.get(419)), r.point(), "419 is resolvable (419 % 3 != 0)");
    }

    @Test
    public void theLastResolvableFileCanBeTheLastImported() throws InterruptedException {
        List<ItemKey> keys = hourly();
        DestinationProbe.Result r = DestinationProbe.probe(keys,
                i -> i >= 415 && i < 420 ? Optional.empty() : Optional.of(Path.of("nas", "d" + (i / 10))),
                DestinationProbeTest::imported);

        assertEquals(ResumePoint.after(keys.get(414)), r.point(), "415..419 cannot be probed: they stay selected");
    }

    @Test
    public void nothingResolvableSelectsEverything() throws InterruptedException {
        DestinationProbe.Result r = DestinationProbe.probe(hourly(), i -> Optional.empty(), dir -> true);
        assertEquals(ResumePoint.all(), r.point());
        assertNull(r.warning());
    }

    /** The day directories d0..d99 of {@link #hourly()} that exist at the destination. */
    private static DestinationProbe.Targets days() {
        return i -> Optional.of(Path.of("nas", "d" + (i / 10)));
    }

    private static Predicate<Path> existing(int fromDay, int toDay, int... deletedDays) {
        Set<Integer> deleted = new HashSet<>();
        for (int day : deletedDays) {
            deleted.add(day);
        }
        return dir -> {
            int day = Integer.parseInt(dir.getFileName().toString().substring(1));
            return day >= fromDay && day <= toDay && !deleted.contains(day);
        };
    }

    @Test
    public void aDayDeletedAtTheDestinationDoesNotMoveThePointBack() throws InterruptedException {
        List<ItemKey> keys = hourly();
        // d20..d25 deleted: a dichotomy would first fall on d24 (index 249), then stop before d20
        DestinationProbe.Result r = DestinationProbe.probe(keys, days(), existing(0, 41, 20, 21, 22, 23, 24, 25));

        assertEquals(ResumePoint.after(keys.get(419)), r.point(), "the files after the deleted days are found");
        assertNull(r.warning(), "the day before the point is there");
    }

    @Test
    public void theOldestDaysDeletedAtTheDestination() throws InterruptedException {
        List<ItemKey> keys = hourly();
        DestinationProbe.Result r = DestinationProbe.probe(keys, days(), existing(31, 41));

        assertEquals(ResumePoint.after(keys.get(419)), r.point());
    }

    @Test
    public void aShortPartKeptAfterADeletedOneIsFound() throws InterruptedException {
        List<ItemKey> keys = hourly();
        // d30..d39 deleted: only d40 and d41 are left after them, a few probes of the rest would miss them
        DestinationProbe.Result r = DestinationProbe.probe(keys, days(),
                existing(0, 41, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39));

        assertEquals(ResumePoint.after(keys.get(419)), r.point());
    }

    @Test
    public void severalGapsAreGoneThroughOneAfterTheOther() throws InterruptedException {
        List<ItemKey> keys = hourly();
        Predicate<Path> exists = existing(0, 89, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
                40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 70, 71, 72, 73, 74, 75);

        DestinationProbe.Result r = DestinationProbe.probe(keys, days(), exists);

        assertEquals(ResumePoint.after(keys.get(899)), r.point());
    }

    @Test
    public void everythingImportedButADeletedDay() throws InterruptedException {
        List<ItemKey> keys = hourly();
        DestinationProbe.Result r = DestinationProbe.probe(keys, days(), existing(0, 99, 49));

        assertEquals(ResumePoint.after(keys.get(999)), r.point());
    }

    @Test
    public void aPointWhoseDayBeforeIsMissingIsWarned() throws InterruptedException {
        List<ItemKey> keys = hourly();
        // d99 filled by another source (or a file dated in the future), or d42..d98 deleted: the point is the last
        // file found, the user is asked to check it
        DestinationProbe.Result r = DestinationProbe.probe(keys, days(), existing(0, 41).or(existing(99, 99)));

        assertEquals(ResumePoint.after(keys.get(999)), r.point());
        assertEquals(ResourcesEngine.getString("resume.warn.isolated-point", "F999",
                ResumeResolver.displayDate(keys.get(999).date())), r.warning());
        assertTrue(r.toCheck(), "a point to check");
    }

    @Test
    public void theDayBeforeThePointIsFoundExactly() throws InterruptedException {
        List<ItemKey> keys = hourly();
        // d0 = 0..499 (deleted), d1 = 500..509, d2 = 510..999: looking back from the point by doubling steps jumps
        // over the short d1 into d0
        DestinationProbe.Targets uneven = i -> Optional.of(Path.of("nas", "d" + (i < 500 ? 0 : i < 510 ? 1 : 2)));

        DestinationProbe.Result r = DestinationProbe.probe(keys, uneven, existing(1, 2));

        assertEquals(ResumePoint.after(keys.get(999)), r.point());
        assertNull(r.warning(), "d1, just before the point, is there");
    }

    @Test
    public void theDayBeforeThePointIsFoundAcrossFilesThatCannotBeProbed() throws InterruptedException {
        List<ItemKey> keys = hourly().subList(0, 9);
        // 0 in x, 1..4 cannot be probed, 5 in y (deleted), 6..8 in t: the day before the point is y, not x
        DestinationProbe.Targets targets = i -> i == 0 ? Optional.of(Path.of("nas", "x"))
                : i < 5 ? Optional.empty() : Optional.of(Path.of("nas", i == 5 ? "y" : "t"));

        DestinationProbe.Result r = DestinationProbe.probe(keys, targets, Set.of(Path.of("nas", "x"), Path.of("nas", "t"))::contains);

        assertEquals(ResumePoint.after(keys.get(8)), r.point());
        assertNotNull(r.warning(), "y is missing");
        assertTrue(r.toCheck());
    }

    @Test
    public void theDayBeforeABigLastDayIsLookedForByBatches() throws InterruptedException {
        List<ItemKey> keys = hourly();
        // d0 = 0..99, d1 = 100..999 (the last day, imported): the day before is looked for by batches, not one by one
        List<Integer> oneByOne = new ArrayList<>();
        Set<Integer> batched = new HashSet<>();
        DestinationProbe.Targets targets = new DestinationProbe.Targets() {
            @Override
            public Optional<Path> dirOf(int index) {
                if (!batched.contains(index)) {
                    oneByOne.add(index);
                }
                return Optional.of(Path.of("nas", "d" + (index < 100 ? 0 : 1)));
            }

            @Override
            public void resolve(List<Integer> indices) {
                batched.addAll(indices);
            }
        };

        DestinationProbe.Result r = DestinationProbe.probe(keys, targets, dir -> true);

        assertEquals(ResumePoint.after(keys.get(999)), r.point());
        assertNull(r.warning());
        assertEquals(List.of(0, 999), oneByOne, "only the first and the last, for the single-directory guard");
    }

    @Test
    public void theFileMatchChecksTheFileBeforeThePoint() throws InterruptedException {
        List<ItemKey> keys = hourly();
        DestinationProbe.Result r = DestinationProbe.probe(keys, i -> Optional.of(Path.of("nas", "f" + i)),
                dir -> {
                    int i = Integer.parseInt(dir.getFileName().toString().substring(1));
                    return i < 420 || i == 999;
                }, false);

        assertEquals(ResumePoint.after(keys.get(999)), r.point());
        assertNotNull(r.warning());
    }

    @Test
    public void aPointInTheFirstDayIsNotWarned() throws InterruptedException {
        List<ItemKey> keys = hourly();
        DestinationProbe.Result r = DestinationProbe.probe(keys, days(), existing(0, 0));

        assertEquals(ResumePoint.after(keys.get(9)), r.point());
        assertNull(r.warning());
    }

    @Test
    public void nothingAtTheDestinationIsNoGap() throws InterruptedException {
        DestinationProbe.Result r = DestinationProbe.probe(hourly(), days(), dir -> false);
        assertEquals(ResumePoint.all(), r.point());
        assertNull(r.warning());
    }

    @Test
    public void emptyListSelectsEverything() {
        assertEquals(ResumePoint.all(), DestinationProbe.probe(List.of(), dir -> true).point());
    }
}
