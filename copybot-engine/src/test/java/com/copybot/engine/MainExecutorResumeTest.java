package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.*;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resume.*;
import com.copybot.resources.ResourcesEngine;
import com.copybot.plugin.api.action.*;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class MainExecutorResumeTest {

    @TempDir
    Path tempDir;

    abstract static class FakeAction implements IAction {
        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    /** Emits one item per day of September, dated through lastModified. */
    final class DatedIn extends FakeAction implements IInAction {
        final int days;

        DatedIn(int days) {
            this.days = days;
        }

        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            for (int day = 1; day <= days; day++) {
                try {
                    WorkItem wi = new WorkItem(Files.createFile(tempDir.resolve(String.format("IMG_%02d.JPG", day))));
                    wi.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED,
                            Instant.parse(String.format("2026-09-%02dT10:00:00Z", day)));
                    consumer.accept(wi);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    static final class RecordingAnalyze extends FakeAction implements IAnalyzeAction {
        final Set<String> seen = ConcurrentHashMap.newKeySet();

        @Override
        public void doAnalyze(WorkItem item) {
            seen.add(item.getNameDisplay());
        }
    }

    static final class RecordingOut extends FakeAction implements IOutAction {
        final Set<String> written = ConcurrentHashMap.newKeySet();
        final String failOn;

        RecordingOut(String failOn) {
            this.failOn = failOn;
        }

        @Override
        public void writeItem(WorkItem item) {
            if (item.getNameDisplay().equals(failOn)) {
                throw new IllegalStateException("write failed");
            }
            written.add(item.getNameDisplay());
        }
    }

    static PipelineStepConfig emptyConfig() {
        return new PipelineStepConfig(null, null, null, null, null, null, null, null);
    }

    static ResourceRegistry registry() {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, Map.of("disk:*", 1000), null)));
    }

    private ResumeStateStore store() {
        return new ResumeStateStore(tempDir.resolve("p.state.json"));
    }

    private MainExecutor executor(int days, RecordingAnalyze analyze, RecordingOut out, ResumeMode mode) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(days), emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(mode, store()));
    }

    private static ItemKey day(int d) {
        return new ItemKey(Instant.parse(String.format("2026-09-%02dT10:00:00Z", d)), String.format("IMG_%02d.JPG", d));
    }

    @Test
    public void prepareRunsAnalysesButNothingAfterTheBarrier() {
        RecordingAnalyze analyze = new RecordingAnalyze();
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(3, analyze, out, ResumeMode.STATE);

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(3, analyze.seen.size(), "analyses run for every listed item");
        assertTrue(out.written.isEmpty(), "nothing after the barrier runs while preparing");
        assertTrue(exec.getState().getWorkItems().stream().allMatch(w -> w.getStatus() == ItemStatus.PENDING));
        assertTrue(exec.getState().getWorkItems().stream().allMatch(WorkItemExecution::isPrepared),
                "every item reached the barrier: the preparation progress is complete");
        assertEquals(ResumeSource.NONE, exec.getState().getResumeProposal().source());
        assertFalse(Files.exists(store().getPath()), "preparing never writes the state file");
    }

    @Test
    public void executeCopiesOnlyAfterTheCursorThenAdvancesIt() {
        store().writeCursor(day(2));
        RecordingAnalyze analyze = new RecordingAnalyze();
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(4, analyze, out, ResumeMode.STATE);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "skipped items are not failures");
        assertEquals(Set.of("IMG_03.JPG", "IMG_04.JPG"), out.written);
        assertEquals(2, exec.getState().getWorkItems().stream().filter(w -> w.getStatus() == ItemStatus.SKIPPED).count());
        assertEquals(day(4), store().readCursor().orElseThrow());
    }

    @Test
    public void failureInTheMiddleStopsTheCursorBeforeIt() {
        RecordingOut out = new RecordingOut("IMG_02.JPG");
        MainExecutor exec = executor(3, new RecordingAnalyze(), out, ResumeMode.STATE);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(day(1), store().readCursor().orElseThrow());
    }

    @Test
    public void manualOverrideSelectsFromTheChosenItem() {
        store().writeCursor(day(3));
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(3, new RecordingAnalyze(), out, ResumeMode.STATE);

        exec.prepare();
        assertTrue(exec.getOrderedItems().stream().allMatch(w -> w.getStatus() == ItemStatus.SKIPPED));
        exec.execute(ResumePoint.from(day(2)));

        assertEquals(Set.of("IMG_02.JPG", "IMG_03.JPG"), out.written);
        assertEquals(day(3), store().readCursor().orElseThrow(), "the cursor never moves back");
    }

    @Test
    public void theItemsBeforeTheStateCursorAreSkippedAtTheListingWithoutBeingAnalysed() {
        store().writeCursor(day(2));
        RecordingAnalyze analyze = new RecordingAnalyze();
        MainExecutor exec = executor(4, analyze, new RecordingOut(null), ResumeMode.STATE);

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(Set.of("IMG_03.JPG", "IMG_04.JPG"), analyze.seen, "the old files are never read");
        List<WorkItemExecution> items = exec.getOrderedItems();
        assertEquals(List.of(ItemStatus.SKIPPED, ItemStatus.SKIPPED, ItemStatus.PENDING, ItemStatus.PENDING),
                items.stream().map(WorkItemExecution::getStatus).toList());
        String cursorDate = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(day(2).date());
        assertEquals(ResourcesEngine.getString("resume.skip.state", "IMG_02.JPG", cursorDate), items.get(0).getSkipReason());
        assertTrue(items.stream().allMatch(WorkItemExecution::isPrepared), "the preparation progress is complete");
        assertTrue(items.get(0).isAnalysisDeferred());
        assertNull(items.get(0).getProjection(), "not analysed: no dry run");
        assertFalse(items.get(2).isAnalysisDeferred());
    }

    @Test
    public void stateThenDestinationAlsoSkipsAtTheListingWhenThereIsACursor() {
        store().writeCursor(day(2));
        RecordingAnalyze analyze = new RecordingAnalyze();
        MainExecutor exec = executor(3, analyze, new RecordingOut(null), ResumeMode.STATE_THEN_DESTINATION);

        exec.prepare();

        assertEquals(Set.of("IMG_03.JPG"), analyze.seen);
        assertEquals(ResumeSource.STATE, exec.getState().getResumeProposal().source());
    }

    @Test
    public void withoutCursorEveryItemIsAnalysed() {
        RecordingAnalyze analyze = new RecordingAnalyze();
        MainExecutor exec = executor(3, analyze, new RecordingOut(null), ResumeMode.STATE);

        exec.prepare();

        assertEquals(3, analyze.seen.size());
        assertTrue(exec.getOrderedItems().stream().noneMatch(WorkItemExecution::isAnalysisDeferred));
    }

    @Test
    public void aManualPointSelectingItemsSkippedAtTheListingAnalysesThemAtTheExecution() {
        store().writeCursor(day(3));
        RecordingAnalyze analyze = new RecordingAnalyze();
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(4, analyze, out, ResumeMode.STATE);

        exec.prepare();
        assertEquals(Set.of("IMG_04.JPG"), analyze.seen);
        exec.execute(ResumePoint.from(day(2)));

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_02.JPG", "IMG_03.JPG", "IMG_04.JPG"), analyze.seen, "analysed before being copied");
        assertEquals(Set.of("IMG_02.JPG", "IMG_03.JPG", "IMG_04.JPG"), out.written);
        assertEquals(day(4), store().readCursor().orElseThrow());
    }

    @Test
    public void modeNoneNeverWritesAStateFile() {
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = executor(2, new RecordingAnalyze(), out, ResumeMode.NONE);

        exec.prepare();
        exec.execute(null);

        assertEquals(2, out.written.size());
        assertFalse(Files.exists(store().getPath()));
    }

    @Test
    public void invalidStateFileFailsThePreparation() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        assertDoesNotThrow(exec::prepare);
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure(), "the cause of the failure is in the state");
        assertThrows(IllegalStateException.class, () -> exec.execute(null));
    }

    /** Forks every item into itself plus a FORK_ sibling. */
    final class ForkingProcess extends FakeAction implements IProcessAction {
        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            try {
                WorkItem fork = new WorkItem(Files.createFile(tempDir.resolve(item.getNameDisplay().replace("IMG_", "FORK_"))));
                return List.of(item, fork);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    public void failedForkHoldsTheCursorBeforeItsParent() {
        RecordingOut out = new RecordingOut("FORK_02.JPG");
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(2), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new ForkingProcess(), emptyConfig()),
                        new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(day(1), store().readCursor().orElseThrow());
    }

    /** Replaces every item by a new file OUT_xx without any date (e.g. a transcoding step). */
    final class ReplacingProcess extends FakeAction implements IProcessAction {
        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            try {
                return List.of(new WorkItem(Files.createFile(tempDir.resolve(item.getNameDisplay().replace("IMG_", "OUT_")))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private MainExecutor replacingExecutor(int days, RecordingOut out) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(days), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new ReplacingProcess(), emptyConfig()),
                        new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));
    }

    @Test
    public void cursorUsesTheKeyFrozenAtTheBarrierWhenAStepReplacesTheItem() {
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = replacingExecutor(3, out);

        exec.run();

        assertEquals(Set.of("OUT_01.JPG", "OUT_02.JPG", "OUT_03.JPG"), out.written);
        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(day(3), store().readCursor().orElseThrow(), "the cursor is the original key of the last item");
    }

    @Test
    public void cursorStopsBeforeAReplacedItemThatFailed() {
        RecordingOut out = new RecordingOut("OUT_02.JPG");
        MainExecutor exec = replacingExecutor(3, out);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals(day(1), store().readCursor().orElseThrow());
    }

    /** Blocks every write until interrupted, signalling when the first write started. */
    static final class BlockingOut extends FakeAction implements IOutAction {
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void writeItem(WorkItem item) {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
    }

    @Test
    public void cancelledExecuteLeavesTheCursorUnchanged() throws Exception {
        store().writeCursor(day(1));
        byte[] before = Files.readAllBytes(store().getPath());
        BlockingOut out = new BlockingOut();
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(3), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));
        exec.prepare();

        Thread runner = Thread.ofVirtual().start(() -> exec.execute(null));
        assertTrue(out.entered.await(10, TimeUnit.SECONDS), "a write started");
        runner.interrupt();
        runner.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(runner.isAlive());
        assertEquals(PipelineStatus.CANCELLED, exec.getState().getStatus());
        assertArrayEquals(before, Files.readAllBytes(store().getPath()), "a cancelled run never moves the cursor");
    }

    @Test
    public void engineRefusesToExecuteAPlanThatIsNotPrepared() throws Exception {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);
        exec.prepare();

        try (CopybotEngine engine = new CopybotEngine(new CopybotConfig(null, null, Map.of(), null))) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> engine.execute(new Plan(exec), null));
            assertFalse(refused.getMessage().startsWith("%"), "a message read from the bundles: " + refused.getMessage());
            assertEquals(ResourcesEngine.getString("engine.not-prepared", PipelineStatus.ERROR), refused.getMessage());
            assertTrue(refused.getMessage().contains("ERROR"), "the status is cited: " + refused.getMessage());

            RecordingOut out = new RecordingOut(null);
            Plan next = engine.prepare(executor(0, new RecordingAnalyze(), out, ResumeMode.NONE));
            assertEquals(PipelineStatus.PREPARED, next.getState().getStatus(), "a refused execute never keeps the engine busy");
            assertEquals(PipelineStatus.SUCCESS, ControlFakes.awaitStatus(engine.execute(next, null)));
        }
    }

    @Test
    public void cursorWriteFailureEndsInErrorAfterTheCopies() throws IOException {
        Path file = Files.createFile(tempDir.resolve("afile"));
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(2), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(),
                new ResumeContext(ResumeMode.STATE, new ResumeStateStore(file.resolve("p.state.json"))));

        exec.run();

        assertEquals(2, out.written.size(), "the files were copied");
        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure());
    }

    @Test
    public void destinationModeWithoutTargetPathsIsAPreparationFailureNotAnException() {
        // RecordingOut does not implement resolveTarget
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.DESTINATION);

        assertDoesNotThrow(exec::prepare);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure());
    }

    @Test
    public void anUnresolvableStepIsAPreparationFailureNotAnException() {
        PipelineStepConfig unknown = new PipelineStepConfig("no.such.plugin", "file.read", null, null, null, null, null, null);
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(unknown), null, null, null, null, null),
                null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        assertDoesNotThrow(exec::prepare);

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertNotNull(exec.getState().getFailure());
    }

    static final class FailingIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
            throw new IllegalStateException("listing boom");
        }
    }

    @Test
    public void aListingFailureIsAPreparationFailureWithItsCause() {
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new FailingIn(), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertEquals("listing boom", exec.getState().getFailure().getMessage());
    }

    // ---- isPreparationFailed: the CLI exits 2 on a preparation failure, 1 on a listing or run failure ----

    @Test
    public void anInvalidStateFileMarksThePreparationFailed() throws IOException {
        Files.writeString(store().getPath(), "not json");
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
    }

    @Test
    public void destinationModeWithoutTargetPathsMarksThePreparationFailed() {
        MainExecutor exec = executor(2, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.DESTINATION);

        exec.prepare();

        assertTrue(exec.getState().isPreparationFailed());
    }

    @Test
    public void anUnresolvableStepMarksThePreparationFailed() {
        PipelineStepConfig unknown = new PipelineStepConfig("no.such.plugin", "file.read", null, null, null, null, null, null);
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(unknown), null, null, null, null, null),
                null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertTrue(exec.getState().isPreparationFailed());
    }

    @Test
    public void anUnresolvableStepMarksThePreparationFailedWithoutResumeBlock() {
        PipelineStepConfig unknown = new PipelineStepConfig("no.such.plugin", "file.read", null, null, null, null, null, null);
        MainExecutor exec = new MainExecutor(new PipelineConfig(List.of(unknown), null, null, null, null, null),
                null, registry(), null);

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
    }

    @Test
    public void aListingFailureDoesNotMarkThePreparationFailed() {
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new FailingIn(), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertFalse(exec.getState().isPreparationFailed(), "a listing failure is a run failure (exit code 1)");
    }

    @Test
    public void aCursorWriteFailureDoesNotMarkThePreparationFailed() throws IOException {
        Path file = Files.createFile(tempDir.resolve("afile"));
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(2), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(),
                new ResumeContext(ResumeMode.STATE, new ResumeStateStore(file.resolve("p.state.json"))));

        exec.run();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertFalse(exec.getState().isPreparationFailed());
    }

    /** Reports the item named skipOn as skipped through write(item, context), writes the others. */
    static final class SkippingOut extends FakeAction implements IOutAction {
        final Set<String> written = ConcurrentHashMap.newKeySet();
        final Set<String> runIds = ConcurrentHashMap.newKeySet();
        final String skipOn;

        SkippingOut(String skipOn) {
            this.skipOn = skipOn;
        }

        @Override
        public void writeItem(WorkItem item) {
            throw new AssertionError("the engine calls write(item, context)");
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            runIds.add(context.runId());
            if (item.getNameDisplay().equals(skipOn)) {
                return WriteResult.skipped(null, "identical to the destination");
            }
            written.add(item.getNameDisplay());
            return WriteResult.written(null);
        }
    }

    /** DatedIn -> RecordingAnalyze -> out; a null resume runs a single phase. */
    private MainExecutor outExecutor(int days, IOutAction out, ResumeContext resume) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(days), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()), new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), resume);
    }

    private static WorkItemExecution named(MainExecutor exec, String name) {
        return exec.getState().getWorkItems().stream()
                .filter(w -> w.getWorkItem().getNameDisplay().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    public void anItemSkippedByTheOutStepEndsSkippedWithItsReason() {
        SkippingOut out = new SkippingOut("IMG_02.JPG");
        MainExecutor exec = outExecutor(3, out, new ResumeContext(ResumeMode.NONE, store()));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus(), "a skipped item is not a failure");
        assertEquals(Set.of("IMG_01.JPG", "IMG_03.JPG"), out.written);
        WorkItemExecution skipped = named(exec, "IMG_02.JPG");
        assertEquals(ItemStatus.SKIPPED, skipped.getStatus());
        assertEquals("identical to the destination", skipped.getSkipReason());
        assertEquals(ItemStatus.DONE, named(exec, "IMG_01.JPG").getStatus());
    }

    @Test
    public void aSinglePhaseRunAlsoEndsTheSkippedItemsSkipped() {
        SkippingOut out = new SkippingOut("IMG_01.JPG");
        MainExecutor exec = outExecutor(2, out, null);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(ItemStatus.SKIPPED, named(exec, "IMG_01.JPG").getStatus());
        assertEquals(ItemStatus.DONE, named(exec, "IMG_02.JPG").getStatus());
    }

    @Test
    public void theOutStepGetsTheRunIdOfItsExecution() {
        SkippingOut out = new SkippingOut(null);
        MainExecutor exec = outExecutor(3, out, new ResumeContext(ResumeMode.NONE, store()));

        exec.run();

        assertEquals(Set.of(exec.getRunId()), out.runIds, "one run id for every item of the execution");
        assertNotEquals(exec.getRunId(), outExecutor(1, new SkippingOut(null), null).getRunId(),
                "one run id per execution");
    }

    @Test
    public void anItemSkippedByTheOutStepAdvancesTheCursor() {
        SkippingOut out = new SkippingOut("IMG_03.JPG"); // the last one: already at the destination
        MainExecutor exec = outExecutor(3, out, new ResumeContext(ResumeMode.STATE, store()));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(day(3), store().readCursor().orElseThrow(), "a skipped item counts as imported");
    }

    /** A listing of nothing whose configuration is valid but risky. */
    static final class WarningIn extends FakeAction implements IInAction {
        @Override
        public void listFiles(Consumer<WorkItem> consumer) {
        }

        @Override
        public List<String> configWarnings() {
            return List.of("risky listing");
        }
    }

    /** An analyse step whose configuration is valid but risky. */
    static final class WarningAnalyze extends FakeAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public List<String> configWarnings() {
            return List.of("risky analyse");
        }
    }

    private MainExecutor warningExecutor(ResumeContext resume) {
        return new MainExecutor(
                List.of(new PipelineStep<>(null, new WarningIn(), emptyConfig())),
                List.of(new PipelineStep<>(null, new WarningAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(), resume);
    }

    @Test
    public void theConfigWarningsOfEveryStepAreInTheStateOnceThePipelineIsPrepared() {
        MainExecutor exec = warningExecutor(new ResumeContext(ResumeMode.STATE, store()));
        assertEquals(List.of(), exec.getState().getWarnings(), "nothing before the steps are resolved");

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(List.of("risky listing", "risky analyse"), exec.getState().getWarnings());
    }

    @Test
    public void aSinglePhaseRunAlsoPublishesTheConfigWarnings() {
        MainExecutor exec = warningExecutor(null);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(List.of("risky listing", "risky analyse"), exec.getState().getWarnings());
    }

    @Test
    public void stepsWithoutWarningsLeaveTheWarningsEmpty() {
        MainExecutor exec = executor(1, new RecordingAnalyze(), new RecordingOut(null), ResumeMode.STATE);

        exec.prepare();

        assertEquals(List.of(), exec.getState().getWarnings());
    }

    /** An analyse step whose configWarnings returns null. */
    static final class NullWarningAnalyze extends FakeAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public List<String> configWarnings() {
            return null;
        }
    }

    @Test
    public void aNullConfigWarningsContributesNothing() {
        IAnalyzeAction nullWarnings = new NullWarningAnalyze();
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new WarningIn(), emptyConfig())),
                List.of(new PipelineStep<>(null, nullWarnings, emptyConfig())),
                1, false, null, registry(), null);

        exec.run();

        assertEquals(List.of("risky listing"), exec.getState().getWarnings());
    }

    /** An out step whose write returns null: a plugin that has nothing to report. */
    static final class NullResultOut extends FakeAction implements IOutAction {
        @Override
        public void writeItem(WorkItem item) {
        }

        @Override
        public WriteResult write(WorkItem item, WriteContext context) {
            return null;
        }
    }

    @Test
    public void anItemWhoseOutStepReturnsANullWriteResultEndsDone() {
        MainExecutor exec = outExecutor(1, new NullResultOut(), null);

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(ItemStatus.DONE, named(exec, "IMG_01.JPG").getStatus());
    }

    @Test
    public void withoutStepAfterTheBarrierTheResumePointStillDecidesWhichItemsAreDone() {
        store().writeCursor(day(2));
        RecordingAnalyze analyze = new RecordingAnalyze();
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(3), emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertEquals(PipelineStatus.PREPARED, exec.getState().getStatus());
        assertEquals(ItemStatus.SKIPPED, named(exec, "IMG_01.JPG").getStatus(), "before the cursor");
        assertEquals(ItemStatus.SKIPPED, named(exec, "IMG_02.JPG").getStatus(), "before the cursor");
        assertEquals(ItemStatus.PENDING, named(exec, "IMG_03.JPG").getStatus(), "an item is never done while preparing");

        exec.execute(null);

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(ItemStatus.DONE, named(exec, "IMG_03.JPG").getStatus());
        assertEquals(day(3), store().readCursor().orElseThrow());
    }

    /** Filters IMG_02.JPG out, lets the others through. */
    static final class FilteringProcess extends FakeAction implements IProcessAction {
        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return item.getNameDisplay().equals("IMG_02.JPG") ? List.of() : List.of(item);
        }
    }

    @Test
    public void anItemFilteredOutAfterTheBarrierEndsDoneAndAdvancesTheCursor() {
        RecordingOut out = new RecordingOut(null);
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(3), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new FilteringProcess(), emptyConfig()),
                        new PipelineStep<>(null, out, emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(Set.of("IMG_01.JPG", "IMG_03.JPG"), out.written);
        assertEquals(ItemStatus.DONE, named(exec, "IMG_02.JPG").getStatus(), "filtered out: nothing left to do");
        assertEquals(day(3), store().readCursor().orElseThrow(), "a filtered item does not hold the cursor back");
    }

    @Test
    public void eachPhaseEndsWithATerminalNotificationOfItsOutcome() {
        List<PipelineStatus> seen = new CopyOnWriteArrayList<>();
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(2), emptyConfig())),
                List.of(new PipelineStep<>(null, new RecordingAnalyze(), emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, state -> seen.add(state.getStatus()), registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();
        assertEquals(PipelineStatus.PREPARED, seen.getLast(), "prepare() notifies its outcome before returning");

        exec.execute(null);
        assertEquals(PipelineStatus.SUCCESS, seen.getLast(), "execute() notifies its outcome before returning");
    }

    /** An analyse step whose configWarnings fails (a plugin bug). */
    static final class ThrowingWarningAnalyze extends FakeAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public List<String> configWarnings() {
            throw new IllegalStateException("warnings failed");
        }
    }

    @Test
    public void aThrowingConfigWarningsIsAPreparationFailureWithItsCause() {
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(1), emptyConfig())),
                List.of(new PipelineStep<>(null, new ThrowingWarningAnalyze(), emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.prepare();

        assertEquals(PipelineStatus.ERROR, exec.getState().getStatus());
        assertTrue(exec.getState().isPreparationFailed());
        assertEquals("warnings failed", exec.getState().getFailure().getMessage());
    }

    /** Counts its configWarnings calls. */
    static final class CountingWarningAnalyze extends FakeAction implements IAnalyzeAction {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public List<String> configWarnings() {
            calls.incrementAndGet();
            return List.of("risky analyse");
        }
    }

    @Test
    public void theConfigWarningsAreCollectedOnceForThePreparationAndTheExecution() {
        CountingWarningAnalyze analyze = new CountingWarningAnalyze();
        MainExecutor exec = new MainExecutor(
                List.of(new PipelineStep<>(null, new DatedIn(2), emptyConfig())),
                List.of(new PipelineStep<>(null, analyze, emptyConfig()),
                        new PipelineStep<>(null, new RecordingOut(null), emptyConfig())),
                1, false, null, registry(), new ResumeContext(ResumeMode.STATE, store()));

        exec.run();

        assertEquals(PipelineStatus.SUCCESS, exec.getState().getStatus());
        assertEquals(1, analyze.calls.get());
        assertEquals(List.of("risky analyse"), exec.getState().getWarnings());
    }
}
