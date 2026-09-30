package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin API of the safe write (spec safe-write §6): existing out actions keep working unchanged. */
public class WriteApiTest {

    @TempDir
    Path tempDir;

    /** An out action written before write(item, context) existed: it only implements writeItem. */
    static final class LegacyOut implements IOutAction {
        final List<WorkItem> written = new ArrayList<>();

        @Override
        public void writeItem(WorkItem workItem) {
            written.add(workItem);
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    @Test
    public void theDefaultWriteCallsWriteItemAndReportsWritten() throws IOException {
        LegacyOut out = new LegacyOut();
        WorkItem item = new WorkItem(Files.createFile(tempDir.resolve("a.jpg")));

        WriteResult result = out.write(item, WriteContext.newRun());

        assertEquals(List.of(item), out.written);
        assertEquals(WriteResult.Outcome.WRITTEN, result.outcome());
        assertNull(result.target(), "a legacy action cannot tell its target");
        assertFalse(result.isSkipped());
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
    public void anActionHasNoConfigWarningsByDefault() {
        assertEquals(List.of(), new LegacyOut().configWarnings());
    }
}
