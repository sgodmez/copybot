package com.copybot.engine;

import com.copybot.engine.ControlFakes.FakeAction;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.copybot.engine.ControlFakes.day;
import static com.copybot.engine.ControlFakes.emptyConfig;
import static com.copybot.engine.ControlFakes.registry;
import static org.junit.jupiter.api.Assertions.*;

/** The target column and the counters of a prepared plan (spec desktop-ui §5). */
public class PlanTest {

    @TempDir
    Path tempDir;

    /** Emits IMG_01.JPG (100 bytes), IMG_02.JPG (200 bytes), IMG_03.JPG (300 bytes), dated 2026-09-01... */
    final class SizedIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= 3; day++) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(String.format("IMG_%02d.JPG", day))));
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    wi.getMetadatas().setSize(day * 100L);
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /** Writes nothing; its target is nas/&lt;name without extension&gt;/&lt;name&gt;, it cannot resolve failOn. */
    final class TargetOut extends FakeAction implements IOutAction {
        final String failOn;

        TargetOut(String failOn) {
            this.failOn = failOn;
        }

        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public Optional<Path> resolveTarget(WorkItem item) {
            String name = item.getNameDisplay();
            if (name.equals(failOn)) {
                throw new IllegalStateException("no value for a pattern variable");
            }
            return Optional.of(tempDir.resolve("nas").resolve(name.substring(0, name.indexOf('.'))).resolve(name));
        }
    }

    /** SizedIn | barrier | out (mode state); the cursor, when not null, is written first. */
    private Plan prepared(IOutAction out, Integer cursorDay) {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("p.state.json"));
        if (cursorDay != null) {
            store.writeCursor(day(cursorDay));
        }
        List<PipelineStep<?>> itemSteps = out == null ? List.of() : List.of(new PipelineStep<>(null, out, emptyConfig()));
        MainExecutor executor = new MainExecutor(
                List.of(new PipelineStep<>(null, new SizedIn(), emptyConfig())), itemSteps,
                0, false, null, registry(Map.of("disk:*", 1000)), new ResumeContext(ResumeMode.STATE, store));
        executor.prepare();
        assertEquals(PipelineStatus.PREPARED, executor.getState().getStatus());
        return new Plan(executor);
    }

    private static WorkItemExecution named(Plan plan, String name) {
        return plan.getOrderedItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    public void theTargetIsTheDirectoryTheOutStepWouldWriteTo() {
        Plan plan = prepared(new TargetOut(null), null);

        assertEquals(Optional.of(tempDir.resolve("nas").resolve("IMG_02")), plan.targetOf(named(plan, "IMG_02.JPG")));
        assertFalse(Files.exists(tempDir.resolve("nas")), "resolving writes nothing");
    }

    @Test
    public void anUnresolvableTargetIsEmpty() {
        Plan plan = prepared(new TargetOut("IMG_01.JPG"), null);

        assertEquals(Optional.empty(), plan.targetOf(named(plan, "IMG_01.JPG")));
        assertTrue(plan.targetOf(named(plan, "IMG_03.JPG")).isPresent());
    }

    @Test
    public void withoutOutStepThereIsNoTarget() {
        Plan plan = prepared(null, null);

        assertEquals(Optional.empty(), plan.targetOf(named(plan, "IMG_01.JPG")));
    }

    @Test
    public void theCountersFollowTheResumePoint() {
        Plan plan = prepared(new TargetOut(null), 1);

        assertEquals(new Plan.Counts(2, 1, 0, 500), plan.counts(), "IMG_01 is before the cursor");

        plan.preview(ResumePoint.all());

        assertEquals(new Plan.Counts(3, 0, 0, 600), plan.counts());
    }

    @Test
    public void countsOfAnyItemsTellSelectedSkippedAndErrors() throws IOException {
        WorkItemExecution pending = item("a.jpg", 10L);
        WorkItemExecution done = item("b.jpg", 20L);
        done.setDone();
        WorkItemExecution skipped = item("c.jpg", 40L);
        skipped.setSkipped("identical");
        WorkItemExecution failed = item("d.jpg", 80L);
        failed.setError(new IllegalStateException("disk full"));
        WorkItemExecution noSize = item("e.jpg", null);

        assertEquals(new Plan.Counts(3, 1, 1, 30), Plan.Counts.of(List.of(pending, done, skipped, failed, noSize)));
        assertTrue(Plan.Counts.isSelected(ItemStatus.RUNNING));
        assertFalse(Plan.Counts.isSelected(ItemStatus.SKIPPED));
    }

    private WorkItemExecution item(String name, Long size) throws IOException {
        WorkItem wi = new WorkItem(tempDir.resolve(name));
        if (size != null) {
            wi.getMetadatas().setSize(size);
        }
        return new WorkItemExecution(wi, List.of());
    }
}
