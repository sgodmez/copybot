package com.copybot.engine.resume;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    public void emptyListSelectsEverything() {
        assertEquals(ResumePoint.all(), DestinationProbe.probe(List.of(), dir -> true).point());
    }
}
