package com.copybot.engine;

import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunCounts;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WrittenFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The run report built for the run-end actions (spec run-end, "Bilan"). */
public class RunReportsTest {

    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-30T10:05:00Z");
    private static final Instant T2 = Instant.parse("2026-10-30T10:07:30Z");

    @TempDir
    Path dir;

    private WorkItemExecution item(String name) throws IOException {
        return new WorkItemExecution(new WorkItem(Files.writeString(dir.resolve(name), name)), List.of());
    }

    private RunReport report(List<WorkItemExecution> items, RunOutcome outcome, Throwable failure) {
        return RunReports.of(Path.of("pipelines", "sd card.json"), outcome, T0, T1, T2, items,
                List.of(new WrittenFile(Path.of("nas", "a.jpg"), "ab", 2)), List.of(Path.of("nas", "b"), Path.of("nas")),
                List.of("careful"), failure, "run-1");
    }

    @Test
    public void eachItemSaysHowItEnded() throws IOException {
        WorkItemExecution written = item("written.jpg");
        written.setWritten(Path.of("nas", "written.jpg"));
        WorkItemExecution filtered = item("filtered.jpg");
        filtered.setFiltered();
        WorkItemExecution skipped = item("skipped.jpg");
        skipped.setSkippedByOutput("already at the destination", Path.of("nas", "skipped.jpg"));
        WorkItemExecution failed = item("failed.jpg");
        failed.setError(new IllegalStateException("disk full"));
        WorkItemExecution noMessage = item("nomessage.jpg");
        noMessage.setError(new NullPointerException());
        WorkItemExecution byPoint = item("old.jpg");
        byPoint.setSkippedByResumePoint("before the cursor");
        WorkItemExecution ignored = item("ignored.jpg");
        ignored.setIgnored("ignored");
        WorkItemExecution notProcessed = item("pending.jpg");

        RunReport report = report(List.of(written, filtered, skipped, failed, noMessage, byPoint, ignored, notProcessed),
                RunOutcome.ERROR, null);

        String source = dir.toString();
        assertEquals(List.of(
                new RunItem("written.jpg", source, ItemOutcome.WRITTEN, Path.of("nas", "written.jpg"), null),
                new RunItem("filtered.jpg", source, ItemOutcome.FILTERED, null, null),
                new RunItem("skipped.jpg", source, ItemOutcome.SKIPPED, Path.of("nas", "skipped.jpg"), "already at the destination"),
                new RunItem("failed.jpg", source, ItemOutcome.ERROR, null, "disk full"),
                new RunItem("nomessage.jpg", source, ItemOutcome.ERROR, null, "java.lang.NullPointerException")),
                report.items());
        assertEquals(new RunCounts(1, 1, 2, 1), report.counts());
    }

    @Test
    public void itNamesThePipelineAndKeepsTheRun() {
        RunReport report = report(List.of(), RunOutcome.SUCCESS, new IllegalStateException("ignored on a success"));

        assertEquals("sd card", report.pipelineName());
        assertEquals(Path.of("pipelines", "sd card.json"), report.pipelinePath());
        assertEquals(RunOutcome.SUCCESS, report.outcome());
        assertEquals(T0, report.started());
        assertEquals(T1, report.copyStarted());
        assertEquals(T2, report.ended());
        assertNull(report.failure(), "only for a run in error");
        assertEquals(List.of(Path.of("nas"), Path.of("nas", "b")), report.createdDirectories(), "parents first");
        assertEquals(List.of(new WrittenFile(Path.of("nas", "a.jpg"), "ab", 2)), report.writtenFiles());
        assertEquals(List.of("careful"), report.warnings());
        assertEquals("run-1", report.runId());
        assertEquals(Map.of(), report.outputs());
        assertEquals(new RunCounts(0, 0, 0, 0), report.counts());
    }

    @Test
    public void theFailureOfARunInError() {
        assertEquals("card missing", report(List.of(), RunOutcome.ERROR, new IllegalStateException("card missing")).failure());
        assertEquals("java.lang.IllegalStateException",
                report(List.of(), RunOutcome.ERROR, new IllegalStateException()).failure());
        assertNull(report(List.of(), RunOutcome.CANCELLED, new IllegalStateException("x")).failure());
    }

    @Test
    public void withoutPipelineTheNameIsEmpty() {
        RunReport report = RunReports.of(null, RunOutcome.SUCCESS, T0, null, T2, List.of(), List.of(), List.of(),
                List.of(), null, "run-1");

        assertEquals("", report.pipelineName());
        assertNull(report.pipelinePath());
        assertNull(report.copyStarted());
    }

    @Test
    public void theReportOfEachActionHasTheWarningsAndTheOutputsSoFar() {
        RunReport base = report(List.of(), RunOutcome.SUCCESS, null);

        RunReport next = RunReports.with(base, List.of("careful", "archive: 1 file left out"), Map.of("archive", "x.zip"));

        assertEquals(List.of("careful", "archive: 1 file left out"), next.warnings());
        assertEquals(Map.of("archive", "x.zip"), next.outputs());
        assertEquals(base.items(), next.items());
        assertEquals(base.ended(), next.ended());
    }
}
