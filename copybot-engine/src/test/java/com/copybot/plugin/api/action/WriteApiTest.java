package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin API of the safe write (spec safe-write §6). */
public class WriteApiTest {

    @TempDir
    Path tempDir;

    /** The smallest out action: only write, the rest by default. */
    static final class MinimalOut implements IOutAction {
        final List<WorkItem> written = new ArrayList<>();

        @Override
        public WriteResult write(WorkItem workItem, WriteContext context) {
            written.add(workItem);
            return WriteResult.written(null);
        }

    }

    @Test
    public void anOutActionNeedsOnlyWrite() throws IOException {
        MinimalOut out = new MinimalOut();
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("a.jpg")));

        WriteResult result = out.write(item, WriteContext.newRun());

        assertEquals(List.of(item), out.written);
        assertFalse(result.isSkipped());
        assertEquals(java.util.Optional.empty(), out.resolveTarget(item));
        assertEquals(TargetCheck.UNKNOWN, out.checkTarget(item, true));
    }

    @Test
    public void aContextWithoutProgressReportsIntoTheVoid() {
        WriteContext context = new WriteContext("run");

        assertSame(ItemProgress.NONE, context.progress());
        assertSame(ItemProgress.NONE, new WriteContext("run", null).progress());
        context.progress().percent(50); // never throws
    }

    @Test
    public void theProgressOfAnItemKeepsTheRun() {
        List<WorkStatus> reports = new ArrayList<>();
        WriteContext run = WriteContext.newRun();

        WriteContext item = run.withProgress(reports::add);
        item.progress().report(new WorkStatus("copy", -1));
        item.progress().percent(42);

        assertEquals(run.runId(), item.runId());
        assertEquals(List.of(new WorkStatus("copy", -1), new WorkStatus(null, 42)), reports);
    }

    @Test
    public void theCreatedDirectoriesReachTheRunFromEveryItem() {
        List<Path> created = new ArrayList<>();
        WriteContext run = new WriteContext("run", null, created::add);

        run.withProgress(ItemProgress.NONE).directoryCreated(tempDir.resolve("2026"));

        assertEquals(List.of(tempDir.resolve("2026")), created);
        new WriteContext("run").directoryCreated(tempDir); // nobody listens: never throws
        WriteContext.newRun().directoryCreated(tempDir);
    }

    @Test
    public void factoriesBuildWrittenAndSkippedResults() {
        Path target = tempDir.resolve("a.jpg");

        assertEquals(new WriteResult(WriteResult.Outcome.WRITTEN, target, null), WriteResult.written(target));
        WriteResult skipped = WriteResult.skipped(target, "identical");
        assertTrue(skipped.isSkipped());
        assertEquals(target, skipped.target());
        assertEquals("identical", skipped.reason());
    }

    @Test
    public void aSkippedResultNeedsAReason() {
        assertThrows(IllegalArgumentException.class, () -> WriteResult.skipped(null, null));
        assertThrows(IllegalArgumentException.class, () -> WriteResult.skipped(null, " "));
        assertThrows(NullPointerException.class, () -> new WriteResult(null, null, null));
    }

    @Test
    public void theRunIdIsAFileNameFragment() {
        assertNotEquals(WriteContext.newRun().runId(), WriteContext.newRun().runId(), "one id per execution");
        assertFalse(WriteContext.newRun().runId().contains("."));
        assertThrows(NullPointerException.class, () -> new WriteContext(null));
        for (String invalid : List.of("", " ", "a.b", "a/b", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> new WriteContext(invalid), invalid);
        }
    }

    @Test
    public void theFileWrittenCallbackHasNoEffectByDefaultAndWithProgressKeepsIt() {
        Path file = Path.of("a.jpg");
        assertDoesNotThrow(() -> new WriteContext("run").fileWritten(file, "abc", 3));
        assertDoesNotThrow(() -> new WriteContext("run", null, null).fileWritten(file, "abc", 3));
        assertDoesNotThrow(() -> WriteContext.newRun().withProgress(ItemProgress.NONE).fileWritten(file, "abc", 3));

        List<String> told = new ArrayList<>();
        WriteContext context = new WriteContext("run", null, null,
                (target, sha256, size) -> told.add(target + " " + sha256 + " " + size));
        context.fileWritten(file, "abc", 3);
        context.withProgress(ItemProgress.NONE).fileWritten(file, "def", 4);

        assertEquals(List.of(file + " abc 3", file + " def 4"), told);
    }

    @Test
    public void anActionHasNoConfigWarningsByDefault() {
        assertEquals(List.of(), new MinimalOut().configWarnings());
    }
}
