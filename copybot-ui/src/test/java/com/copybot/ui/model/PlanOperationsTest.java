package com.copybot.ui.model;

import com.copybot.engine.Projection;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine operations of the plan view without JavaFX nor engine: the background work is queued and run step by
 * step by the test, the UI thread is the test's own.
 */
public class PlanOperationsTest {

    @TempDir
    Path tempDir;

    /** A plan: its live state and its rows. */
    private static final class FakePlan {
        final PipelineState state = new PipelineState();
        List<WorkItemExecution> items = List.of();
        boolean preparationCancelled;
    }

    /** An accepted execution, over until {@link #finish}; it notifies through the plan's state, like the engine. */
    private static final class FakeRun {
        final PipelineState state;
        final CountDownLatch done = new CountDownLatch(1);

        FakeRun(PipelineState state) {
            this.state = state;
        }

        void finish(PipelineStatus status) {
            state.setStatus(status);
            done.countDown();
        }
    }

    /** What the engine does when called: given by the test before running the background work. */
    private static final class FakeEngine implements PlanOperations.Engine<FakePlan, FakeRun> {
        FakePlan toPrepare;
        RuntimeException refusal;
        Exception awaitFailure;
        FakeRun accepted;
        Consumer<PipelineState> watcher;
        ResumePoint executedOverride;
        int analysed;
        Optional<String> defaultOutPattern = Optional.empty();
        final List<String> ownPatterns = new ArrayList<>();

        @Override
        public FakePlan prepare(Consumer<PipelineState> watcher, Consumer<FakePlan> started, ResumePoint chosen) {
            this.watcher = watcher;
            started.accept(toPrepare);
            if (refusal != null) {
                throw refusal;
            }
            toPrepare.state.setStatus(PipelineStatus.PREPARED);
            return toPrepare;
        }

        @Override
        public FakeRun run(Consumer<PipelineState> watcher, ResumePoint chosen) {
            this.watcher = watcher;
            if (refusal != null) {
                throw refusal;
            }
            return accepted;
        }

        @Override
        public FakeRun execute(FakePlan plan, ResumePoint override) {
            if (refusal != null) {
                throw refusal;
            }
            executedOverride = override;
            accepted = new FakeRun(plan.state);
            accepted.state.setStatus(PipelineStatus.RUNNING);
            return accepted;
        }

        @Override
        public void analyse(FakePlan plan) {
            if (refusal != null) {
                throw refusal;
            }
            analysed++;
            plan.items.forEach(i -> i.setProjection(new Projection.Projected(List.of(i.getWorkItem()))));
            plan.state.setStatus(PipelineStatus.PREPARED);
        }

        @Override
        public Optional<String> defaultOutPattern(FakePlan plan) {
            return defaultOutPattern;
        }

        @Override
        public void setOutPattern(FakePlan plan, List<WorkItemExecution> items, String pattern) {
            if (refusal != null) {
                throw refusal;
            }
            ownPatterns.add(pattern);
            items.forEach(i -> i.getWorkItem().setOutPattern(pattern));
        }

        @Override
        public void await(FakeRun run) throws InterruptedException {
            if (awaitFailure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (awaitFailure != null) {
                throw new IllegalStateException(awaitFailure);
            }
            assertTrue(run.done.getCount() == 0, "awaited by the test only once the run is over");
        }

        @Override
        public PipelineState planState(FakePlan plan) {
            return plan.state;
        }

        @Override
        public List<WorkItemExecution> orderedItems(FakePlan plan) {
            return plan.items;
        }

        @Override
        public Optional<String> executionRefusal(FakePlan plan) {
            return Optional.empty();
        }

        @Override
        public void cancelPreparation(FakePlan plan) {
            plan.preparationCancelled = true;
        }

        @Override
        public void cancelAnalysis(FakePlan plan) {
        }

        @Override
        public PipelineState runState(FakeRun run) {
            return run.state;
        }

        @Override
        public void pause(FakeRun run) {
            run.state.setStatus(PipelineStatus.PAUSED);
        }

        @Override
        public void resume(FakeRun run) {
            run.state.setStatus(PipelineStatus.RUNNING);
        }

        @Override
        public void cancel(FakeRun run) {
            run.state.setStatus(PipelineStatus.CANCELLED);
        }
    }

    /** The view: what it was told. */
    private static final class FakeView implements PlanOperations.View {
        int renders;
        final List<Throwable> errors = new ArrayList<>();
        final List<Path> applied = new ArrayList<>();
        final List<WorkItemExecution> details = new ArrayList<>();
        int scrolls;

        @Override
        public void render() {
            renders++;
        }

        @Override
        public void showError(Throwable failure) {
            errors.add(failure);
        }

        @Override
        public void scrollToResume() {
            scrolls++;
        }

        @Override
        public void showDetail(WorkItemExecution item) {
            details.add(item);
        }

        @Override
        public void applySaved(Path saved) {
            applied.add(saved);
        }
    }

    /** The background threads: the work waits until the test runs it. */
    private final Deque<Runnable> background = new ArrayDeque<>();
    /** The UI thread: the test's own, at once; or later ({@link #uiQueue}) while {@link #uiLater}. */
    private boolean uiLater;
    private final List<Runnable> uiQueue = new ArrayList<>();
    private final Executor ui = task -> {
        if (uiLater) {
            uiQueue.add(task);
        } else {
            task.run();
        }
    };
    private final FakeEngine engine = new FakeEngine();
    private final FakeView view = new FakeView();
    private final PlanOperations<FakePlan, FakeRun> ops =
            new PlanOperations<>(engine, background::add, ui, view);

    private void runNext() {
        assertFalse(background.isEmpty(), "background work expected");
        background.poll().run();
    }

    private WorkItemExecution item(String name) throws IOException {
        WorkItem wi = new WorkItem(tempDir.resolve(name));
        wi.getMetadatas().setSize(10L);
        return new WorkItemExecution(wi, List.of());
    }

    /** The plan the next preparation returns, with these rows to copy. */
    private FakePlan planOf(WorkItemExecution... items) {
        FakePlan plan = new FakePlan();
        plan.items = List.of(items);
        plan.state.getWorkItems().addAll(plan.items);
        engine.toPrepare = plan;
        return plan;
    }

    /** A prepared plan of one row, the engine free. */
    private FakePlan prepared() throws IOException {
        FakePlan plan = planOf(item("a.jpg"));
        ops.prepare();
        runNext();
        assertEquals(Phase.PREPARED, ops.model().phase());
        assertFalse(ops.isEngineHeld());
        return plan;
    }

    // ---- the engine held ----

    @Test
    public void aPreparationHoldsTheEngineUntilPrepareReturns() throws IOException {
        FakePlan plan = planOf(item("a.jpg"));

        ops.prepare();

        assertTrue(ops.isEngineHeld());
        assertEquals(Phase.PREPARING, ops.model().phase());
        assertNull(ops.targetsPlan());
        runNext();
        assertFalse(ops.isEngineHeld(), "prepare() has returned: the engine is free");
        assertFalse(ops.busy());
        assertSame(plan, ops.plan());
        assertEquals(Phase.PREPARED, ops.model().phase());
        assertEquals(1, view.scrolls, "the resume junction is brought into view");
    }

    @Test
    public void aPreparationCanBeStoppedOnceItsPlanIsHandedOut() throws IOException {
        FakePlan plan = planOf(item("a.jpg"));
        ops.prepare();
        assertFalse(ops.isPreparationStoppable());
        ops.stop();
        assertFalse(plan.preparationCancelled, "nothing to stop yet");

        uiLater = true;
        runNext(); // the plan handed out, then prepare() returned: both wait for the UI thread
        uiLater = false;
        uiQueue.removeFirst().run();
        assertTrue(ops.isPreparationStoppable());
        ops.stop();
        assertTrue(plan.preparationCancelled);
        assertTrue(ops.isEngineHeld(), "until prepare() returns");

        uiQueue.removeFirst().run();
        assertFalse(ops.isEngineHeld());
        assertFalse(ops.isPreparationStoppable());
    }

    @Test
    public void aCopyHoldsTheEngineUntilTheExecutionIsReallyOver() throws IOException {
        FakePlan plan = prepared();

        ops.copy();
        assertTrue(ops.isEngineHeld());
        assertEquals(Phase.RUNNING, ops.model().phase());
        runNext(); // execute() accepted it: its end is awaited in the background
        assertTrue(ops.isEngineHeld());

        plan.state.setStatus(PipelineStatus.SUCCESS);
        engine.watcher.accept(plan.state); // the terminal notification comes before the engine is free
        assertEquals(Phase.FINISHED, ops.model().phase());
        assertTrue(ops.isEngineHeld(), "finished in the model, the engine not free yet");
        assertTrue(ops.busy());

        engine.accepted.finish(PipelineStatus.SUCCESS);
        runNext();
        assertFalse(ops.isEngineHeld());
        assertFalse(ops.busy());
        assertTrue(view.errors.isEmpty());
    }

    @Test
    public void theCopyStartsByItselfWhenTheAutomaticExecutionIsChecked() throws IOException {
        planOf(item("a.jpg"));
        ops.model().setAutoExecute(true);

        ops.prepare();
        runNext();

        assertEquals(Phase.RUNNING, ops.model().phase(), "the copy started once the plan was ready");
        assertTrue(ops.isEngineHeld());
        runNext();
        assertNotNull(engine.accepted);
    }

    @Test
    public void anAwaitInterruptedByTheExitReleasesTheEngineSilently() throws IOException {
        prepared();
        ops.copy();
        runNext();
        engine.awaitFailure = new InterruptedException();

        runNext();

        assertFalse(ops.isEngineHeld());
        assertTrue(view.errors.isEmpty(), "the application is closing");
    }

    @Test
    public void anExecutionLostBeforeItsEndDropsThePlan() throws IOException {
        prepared();
        ops.copy();
        runNext();
        engine.awaitFailure = new Exception("lost");

        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(Phase.NOT_PREPARED, ops.model().phase(), "no terminal state will come");
        assertNull(ops.plan());
        assertEquals(1, view.errors.size());
    }

    // ---- refusals ----

    @Test
    public void aRefusedCopyGoesBackToThePreparedPlan() throws IOException {
        FakePlan plan = prepared();
        ResumePoint override = ResumePoint.from(new ItemKey(Instant.parse("2026-01-01T00:00:00Z"), "a.jpg"));
        ops.model().setOverride(override);
        ops.model().setFilter(Filter.TO_COPY);
        IllegalStateException busy = new IllegalStateException("engine busy");
        engine.refusal = busy;

        ops.copy();
        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(Phase.PREPARED, ops.model().phase(), "the phase follows the plan again");
        assertEquals(override, ops.model().override(), "the manual point is kept");
        assertEquals(Filter.TO_COPY, ops.model().filter());
        assertSame(plan, ops.plan());
        assertEquals(List.of(busy), view.errors);
        ops.pause();
        assertTrue(background.isEmpty(), "no execution to pause");
        assertTrue(ops.model().canCopy(), "it can be copied again");
    }

    @Test
    public void aRefusedStreamingRunIsNotPrepared() {
        ops.model().setExecutionMode(ExecutionMode.STREAMING);
        engine.refusal = new IllegalStateException("invalid pipeline");

        ops.prepare();
        assertEquals(Phase.RUNNING, ops.model().phase(), "an execution from the start");
        assertTrue(ops.isEngineHeld());
        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(Phase.NOT_PREPARED, ops.model().phase());
        assertEquals(1, view.errors.size());
    }

    // ---- stale callbacks ----

    @Test
    public void thePlanOfAPreparationDroppedMeanwhileIsIgnored() throws IOException {
        planOf(item("a.jpg"));
        ops.prepare();
        uiLater = true;
        runNext(); // prepare() has run: its results wait for the UI thread
        uiLater = false;
        ops.dropPlan(Filter.ALL); // the pipeline read again

        PipelineState late = new PipelineState();
        late.setStatus(PipelineStatus.RUNNING);
        engine.watcher.accept(late);
        assertEquals(Phase.NOT_PREPARED, ops.model().phase(), "a notification of the dropped preparation");
        assertTrue(ops.isEngineHeld(), "the engine is still held by it: a reload does not free it");

        uiQueue.forEach(Runnable::run); // its plan handed out, then prepare() returned
        assertFalse(ops.isPreparationStoppable(), "the plan of the dropped preparation is not taken");
        assertNull(ops.plan(), "its plan is not shown");
        assertEquals(Phase.NOT_PREPARED, ops.model().phase());
        assertFalse(ops.isEngineHeld(), "its end frees the engine");
    }

    @Test
    public void theStateOfAnotherPlanIsIgnored() throws IOException {
        prepared();
        PipelineState other = new PipelineState();
        other.setStatus(PipelineStatus.RUNNING);

        engine.watcher.accept(other);

        assertEquals(Phase.PREPARED, ops.model().phase());
    }

    @Test
    public void aPauseAppliesOnceTheExecutionIsAccepted() throws IOException {
        prepared();
        ops.copy();
        runNext(); // accepted; its await is queued
        Runnable await = background.poll();

        ops.pause();
        runNext();
        assertEquals(Phase.PAUSED, ops.model().phase(), "the status changed without a notification: read again");

        ops.resume();
        runNext();
        assertEquals(Phase.RUNNING, ops.model().phase());
        engine.accepted.finish(PipelineStatus.SUCCESS);
        await.run();
        assertFalse(ops.isEngineHeld());
    }

    // ---- the files selected again ----

    @Test
    public void theAnalysisOfOneFileShowsItsPlannedProcessing() throws IOException {
        FakePlan plan = prepared();
        WorkItemExecution file = plan.items.getFirst();

        ops.analyse(plan.items, file);
        assertTrue(ops.isEngineHeld());
        assertEquals(Phase.ANALYSING, ops.model().phase());
        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(1, engine.analysed);
        assertEquals(Phase.PREPARED, ops.model().phase());
        assertEquals(List.of(file), view.details, "what it was analysed for");
    }

    @Test
    public void aFailedAnalysisIsReportedWithoutDetail() throws IOException {
        FakePlan plan = prepared();
        engine.refusal = new IllegalStateException("engine closed");

        ops.analyse(plan.items, plan.items.getFirst());
        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(List.of(), view.details);
        assertEquals(1, view.errors.size());
    }

    // ---- own out pattern ----

    @Test
    public void theOwnPatternIsSetInTheBackgroundThenTheRowsAreRefreshed() throws IOException {
        engine.defaultOutPattern = Optional.of("{name}");
        FakePlan plan = prepared();
        assertEquals(Optional.of("{name}"), ops.model().defaultOutPattern(), "the plan's default is known to the model");
        int renders = view.renders;

        ops.setOutPattern(plan.items, "x/{name}");
        assertTrue(ops.isEngineHeld(), "busy meanwhile");
        assertEquals(List.of(), engine.ownPatterns, "not on the calling thread");
        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(List.of("x/{name}"), engine.ownPatterns);
        assertTrue(view.renders > renders, "the rows are refreshed");
        assertEquals(List.of(), view.errors);
    }

    @Test
    public void aRefusedOwnPatternIsReported() throws IOException {
        engine.defaultOutPattern = Optional.of("{name}");
        FakePlan plan = prepared();
        engine.refusal = new IllegalStateException("syntax");

        ops.setOutPattern(plan.items, "{bad");
        runNext();

        assertFalse(ops.isEngineHeld());
        assertEquals(1, view.errors.size());
    }

    @Test
    public void noOwnPatternIsSetWhileTheEngineIsHeld() throws IOException {
        engine.defaultOutPattern = Optional.of("{name}");
        FakePlan plan = prepared();
        ops.analyse(plan.items, null);

        ops.setOutPattern(plan.items, "x");

        assertEquals(1, background.size(), "only the analysis");
    }

    // ---- editor saves ----

    @Test
    public void aSaveDuringAnOperationIsAppliedOnceTheEngineIsFree() throws IOException {
        planOf(item("a.jpg"));
        Path saved = tempDir.resolve("pipeline.json");
        ops.prepare();

        ops.editorSaved(saved);
        assertEquals(List.of(), view.applied, "the engine is held: kept for later");
        runNext();

        assertEquals(List.of(saved), view.applied, "applied by the refresh that follows its end");
        ops.refresh();
        assertEquals(List.of(saved), view.applied, "once");
    }

    @Test
    public void aSaveWhileIdleIsAppliedAtOnce() {
        Path saved = tempDir.resolve("pipeline.json");

        ops.editorSaved(saved);

        assertEquals(List.of(saved), view.applied);
    }

    @Test
    public void droppingThePlanKeepsTheChosenPointAndTheFilter() throws IOException {
        prepared();
        ResumePoint chosen = ResumePoint.all();
        ops.model().setChosenPoint(chosen);
        PlanViewModel before = ops.model();

        ops.dropPlan(Filter.ERRORS);

        assertNotSame(before, ops.model());
        assertNull(ops.plan());
        assertEquals(Phase.NOT_PREPARED, ops.model().phase());
        assertEquals(chosen, ops.model().chosenPoint());
        assertEquals(Filter.ERRORS, ops.model().filter());
    }

}
