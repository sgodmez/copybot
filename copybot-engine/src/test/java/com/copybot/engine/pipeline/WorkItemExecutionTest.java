package com.copybot.engine.pipeline;

import com.copybot.engine.resume.ItemKey;
import com.copybot.plugin.api.action.WorkItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemExecutionTest {

    @TempDir
    Path tempDir;

    private WorkItemExecution newExecution() throws IOException {
        Path file = Files.createFile(tempDir.resolve("item.txt"));
        return new WorkItemExecution(new WorkItem(file), List.of());
    }

    @Test
    public void lifecycleTransitions() throws IOException {
        WorkItemExecution exec = newExecution();
        assertEquals(ItemStatus.PENDING, exec.getStatus());

        exec.setWaitingResources(2, Set.of("cpu", "disk:X"));
        assertEquals(ItemStatus.WAITING_RESOURCES, exec.getStatus());
        assertEquals(2, exec.getCurrentStepIndex());
        assertEquals(Set.of("cpu", "disk:X"), exec.getWaitingFor());

        exec.setRunning(2);
        assertEquals(ItemStatus.RUNNING, exec.getStatus());
        assertEquals(Set.of(), exec.getWaitingFor());

        exec.setDone();
        assertEquals(ItemStatus.DONE, exec.getStatus());
    }

    @Test
    public void errorKeepsTheCause() throws IOException {
        WorkItemExecution exec = newExecution();
        IllegalStateException boom = new IllegalStateException("boom");
        exec.setError(boom);
        assertEquals(ItemStatus.ERROR, exec.getStatus());
        assertSame(boom, exec.getError());
    }

    @Test
    public void workItemCanBeReplacedByProcessSteps() throws IOException {
        WorkItemExecution exec = newExecution();
        WorkItem replacement = new WorkItem(Files.createFile(tempDir.resolve("out.txt")));
        exec.replaceWorkItem(replacement);
        assertSame(replacement, exec.getWorkItem());
    }

    @Test
    public void theResumeKeyIsFrozenByTheFirstCall() throws IOException {
        WorkItemExecution exec = newExecution();
        ItemKey first = new ItemKey(Instant.parse("2026-09-01T10:00:00Z"), "a.jpg");

        exec.setResumeKey(first);
        exec.setResumeKey(new ItemKey(Instant.parse("2026-09-02T10:00:00Z"), "b.jpg"));

        assertEquals(Optional.of(first), exec.getResumeKey(), "a second call is ignored");
    }

    @Test
    public void aResumeKeyFrozenToNoneStaysEmpty() throws IOException {
        WorkItemExecution exec = newExecution();

        exec.setResumeKey(null); // an item without date
        exec.setResumeKey(new ItemKey(Instant.parse("2026-09-02T10:00:00Z"), "b.jpg"));

        assertTrue(exec.getResumeKey().isEmpty());
    }
}
