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
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.resources.ResourcesEngine;
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

        assertEquals(new TargetProjection.Targets(List.of(tempDir.resolve("nas").resolve("IMG_02"))),
                plan.projectionOf(named(plan, "IMG_02.JPG")));
        assertFalse(Files.exists(tempDir.resolve("nas")), "resolving writes nothing");
    }

    @Test
    public void aTargetWithoutDirectoryPartIsTheCurrentDirectory() {
        final class NameOnlyOut extends FakeAction implements IOutAction {
            @Override
            public void writeItem(WorkItem item) {
            }

            @Override
            public Optional<Path> resolveTarget(WorkItem item) {
                return Optional.of(Path.of(item.getNameDisplay())); // e.g. an output pattern "{name}"
            }
        }
        Plan plan = prepared(new NameOnlyOut(), null);

        assertEquals(new TargetProjection.Targets(List.of(Path.of("").toAbsolutePath())),
                plan.projectionOf(named(plan, "IMG_02.JPG")),
                "where the item is written, like the resume probe sees it");
    }

    @Test
    public void anUnresolvableTargetShowsItsMessage() {
        Plan plan = prepared(new TargetOut("IMG_01.JPG"), null);

        assertEquals(new TargetProjection.Failed("no value for a pattern variable"), plan.projectionOf(named(plan, "IMG_01.JPG")));
        assertInstanceOf(TargetProjection.Targets.class, plan.projectionOf(named(plan, "IMG_03.JPG")));
    }

    @Test
    public void withoutOutStepThereIsNoTarget() {
        Plan plan = prepared(null, null);

        assertEquals(TargetProjection.NONE, plan.projectionOf(named(plan, "IMG_01.JPG")));
    }

    @Test
    public void anItemSkippedAtTheListingHasNoTargetNorDetail() {
        Plan plan = prepared(new TargetOut(null), 1);
        WorkItemExecution old = named(plan, "IMG_01.JPG");

        assertEquals(TargetProjection.NONE, plan.projectionOf(old), "not analysed: its target is not known");
        assertTrue(plan.detailOf(old).isEmpty());
        plan.preview(ResumePoint.all());
        assertEquals(TargetProjection.NONE, plan.projectionOf(old), "selected again, still not analysed");
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

    // ---- files ignored by the user ----

    @Test
    public void anIgnoredFileIsSkippedWhateverTheResumePoint() {
        Plan plan = prepared(new TargetOut(null), null);
        WorkItemExecution second = named(plan, "IMG_02.JPG");

        plan.ignore(List.of(second), null);

        assertEquals(ItemStatus.SKIPPED, second.getStatus());
        assertTrue(second.isIgnored());
        assertEquals(ResourcesEngine.getString("plan.skip.ignored"), second.getSkipReason());
        assertEquals(new Plan.Counts(2, 1, 0, 400), plan.counts());

        plan.preview(ResumePoint.all());

        assertEquals(ItemStatus.SKIPPED, second.getStatus(), "a resume point does not select it again");
        assertEquals(new Plan.Counts(2, 1, 0, 400), plan.counts());
    }

    @Test
    public void anUnignoredFileIsDecidedAgainByTheResumePointInForce() {
        Plan plan = prepared(new TargetOut(null), 1);
        WorkItemExecution first = named(plan, "IMG_01.JPG");
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        String cursorReason = first.getSkipReason();
        plan.ignore(List.of(first, second), null);

        plan.unignore(List.of(first, second), null);

        assertFalse(first.isIgnored());
        assertEquals(ItemStatus.SKIPPED, first.getStatus(), "before the cursor");
        assertEquals(cursorReason, first.getSkipReason());
        assertEquals(ItemStatus.PENDING, second.getStatus());

        plan.ignore(List.of(first), ResumePoint.all());
        plan.unignore(List.of(first), ResumePoint.all());

        assertEquals(ItemStatus.PENDING, first.getStatus(), "the manual point given selects it");
    }

    @Test
    public void aFailedFileCannotBeIgnored() {
        MainExecutor executor = new MainExecutor(
                List.of(new PipelineStep<>(null, new PartlyDatedIn(), emptyConfig())), List.of(),
                0, false, null, registry(Map.of("disk:*", 1000)),
                new ResumeContext(ResumeMode.STATE, new ResumeStateStore(tempDir.resolve("p.state.json"))));
        executor.prepare();
        Plan plan = new Plan(executor);
        WorkItemExecution noDate = named(plan, "NODATE.BIN");

        plan.ignore(List.of(noDate), null);

        assertEquals(ItemStatus.ERROR, noDate.getStatus());
        assertFalse(noDate.isIgnored());
    }

    @Test
    public void anIgnoredFileIsNotCopiedAndTheCursorPassesIt() {
        Plan plan = prepared(new TargetOut(null), null);
        WorkItemExecution second = named(plan, "IMG_02.JPG");
        plan.ignore(List.of(second), null);

        plan.getExecutor().execute(null);

        assertEquals(PipelineStatus.SUCCESS, plan.getState().getStatus());
        assertEquals(ItemStatus.DONE, named(plan, "IMG_01.JPG").getStatus());
        assertEquals(ItemStatus.SKIPPED, second.getStatus());
        assertEquals(ItemStatus.DONE, named(plan, "IMG_03.JPG").getStatus());
        assertEquals(Optional.of(day(3)), new ResumeStateStore(tempDir.resolve("p.state.json")).readCursor());
        assertThrows(IllegalStateException.class, () -> plan.ignore(List.of(named(plan, "IMG_01.JPG")), null),
                "an executed plan is not changed any more");
    }

    @Test
    public void theListedPathOfAnItemIsKeptWhenAStepReplacesIt() throws IOException {
        WorkItemExecution listed = item("a.jpg", null);
        listed.replaceWorkItem(new WorkItem(tempDir.resolve("a.webp")));
        WorkItemExecution forked = item("a-2.jpg", null);
        forked.setParent(listed);

        assertEquals(Optional.of(tempDir.resolve("a.jpg")), listed.getListedPath());
        assertEquals(Optional.of(tempDir.resolve("a.jpg")), forked.getListedPath(), "the file it comes from");
    }

    private WorkItemExecution item(String name, Long size) throws IOException {
        WorkItem wi = new WorkItem(tempDir.resolve(name));
        if (size != null) {
            wi.getMetadatas().setSize(size);
        }
        return new WorkItemExecution(wi, List.of());
    }

    /** Emits IMG_01.JPG, dated, and NODATE.BIN, without any date. */
    final class PartlyDatedIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            try {
                WorkItem dated = new WorkItem(Files.createFile(tempDir.resolve("IMG_01.JPG")));
                dated.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, Instant.parse("2026-09-01T10:00:00Z"));
                consumer.accept(dated);
                consumer.accept(new WorkItem(Files.createFile(tempDir.resolve("NODATE.BIN"))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    public void aListedFileWithoutDateCannotBeAResumePointAndSaysWhy() {
        MainExecutor executor = new MainExecutor(
                List.of(new PipelineStep<>(null, new PartlyDatedIn(), emptyConfig())), List.of(),
                0, false, null, registry(Map.of("disk:*", 1000)),
                new ResumeContext(ResumeMode.STATE, new ResumeStateStore(tempDir.resolve("p.state.json"))));
        executor.prepare();
        Plan plan = new Plan(executor);

        assertEquals(ResumePoint.from(day(1)), plan.fromFile("IMG_01.JPG"));
        CopybotException noDate = assertThrows(CopybotException.class, () -> plan.fromFile("NODATE.BIN"));
        assertEquals(ResourcesEngine.getString("resume.from-file.no-date", "NODATE.BIN"), noDate.getMessage());
        CopybotException unknown = assertThrows(CopybotException.class, () -> plan.fromFile("NOPE.JPG"));
        assertEquals(ResourcesEngine.getString("resume.from-file.not-found", "NOPE.JPG"), unknown.getMessage());
    }
}
