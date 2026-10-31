package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The report handed to the end-of-run actions. */
public class RunReportTest {

    private static final Instant START = Instant.parse("2026-10-30T10:00:00Z");

    private static RunReport report(Instant copyStarted, List<RunItem> items, List<WrittenFile> files,
                                    List<Path> dirs, List<String> warnings, Map<String, String> outputs) {
        return new RunReport("holidays", Path.of("holidays.json"), RunOutcome.SUCCESS, START, copyStarted,
                START.plusSeconds(100), new RunCounts(1, 0, 0, 0), items, files, dirs, warnings, null, "run1",
                outputs);
    }

    @Test
    public void theDurationRunsFromTheStartOfTheCopy() {
        assertEquals(Duration.ofSeconds(60),
                report(START.plusSeconds(40), List.of(), List.of(), List.of(), List.of(), Map.of()).duration());
    }

    @Test
    public void theDurationRunsFromTheStartWhenTheCopyDidNotBegin() {
        assertEquals(Duration.ofSeconds(100),
                report(null, List.of(), List.of(), List.of(), List.of(), Map.of()).duration());
    }

    @Test
    public void theListsAndTheOutputsAreDefensiveUnmodifiableCopies() {
        List<RunItem> items = new ArrayList<>(List.of(
                new RunItem("a.jpg", "src", ItemOutcome.WRITTEN, Path.of("t/a.jpg"), null)));
        List<WrittenFile> files = new ArrayList<>(List.of(new WrittenFile(Path.of("t/a.jpg"), "abc", 3)));
        List<Path> dirs = new ArrayList<>(List.of(Path.of("t")));
        List<String> warnings = new ArrayList<>(List.of("w"));
        Map<String, String> outputs = new HashMap<>(Map.of("k", "v"));

        RunReport report = report(null, items, files, dirs, warnings, outputs);
        items.clear();
        files.clear();
        dirs.clear();
        warnings.clear();
        outputs.clear();

        assertEquals(1, report.items().size());
        assertEquals(1, report.writtenFiles().size());
        assertEquals(List.of(Path.of("t")), report.createdDirectories());
        assertEquals(List.of("w"), report.warnings());
        assertEquals(Map.of("k", "v"), report.outputs());
        assertThrows(UnsupportedOperationException.class, () -> report.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.writtenFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.createdDirectories().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.warnings().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.outputs().put("x", "y"));
    }

    @Test
    public void aRunEndActionTimesOutAfterOneMinuteByDefault() {
        IRunEndAction action = (report, context) -> {
        };
        assertEquals(Duration.ofSeconds(60), action.defaultTimeout());
    }
}
