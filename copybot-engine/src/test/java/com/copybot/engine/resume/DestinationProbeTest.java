package com.copybot.engine.resume;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void usesADichotomyNotOneCheckPerFile() {
        List<DestinationProbe.Candidate> big = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            big.add(new DestinationProbe.Candidate(
                    new ItemKey(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L), "F" + i),
                    Path.of("nas", "d" + (i / 10))));
        }
        AtomicInteger checks = new AtomicInteger();
        DestinationProbe.Result r = DestinationProbe.probe(big, dir -> {
            checks.incrementAndGet();
            return Integer.parseInt(dir.getFileName().toString().substring(1)) < 42;
        });
        assertTrue(checks.get() <= 12, "expected ~log2(1000) checks, got " + checks.get());
        assertEquals(ResumePoint.after(big.get(419).key()), r.point(), "the last item of the last existing directory d41");
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
    public void resolvesTheTargetOfTheProbedFilesOnly() throws InterruptedException {
        List<ItemKey> keys = hourly();
        Set<Integer> resolved = new java.util.HashSet<>();
        DestinationProbe.Result r = DestinationProbe.probe(keys, i -> {
            resolved.add(i);
            return Optional.of(Path.of("nas", "d" + (i / 10)));
        }, DestinationProbeTest::imported);

        assertEquals(ResumePoint.after(keys.get(419)), r.point());
        assertTrue(resolved.size() <= 14, "first, last and ~log2(1000) probes, got " + resolved.size());
        assertTrue(resolved.containsAll(Set.of(0, 999)), "the first and the last tell whether the directory varies");
    }

    @Test
    public void anUnresolvableFileIsSkippedByTheDichotomy() throws InterruptedException {
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

    @Test
    public void emptyListSelectsEverything() {
        assertEquals(ResumePoint.all(), DestinationProbe.probe(List.of(), dir -> true).point());
    }
}
