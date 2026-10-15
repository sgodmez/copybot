package com.copybot.ui.model;

import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import com.copybot.ui.util.Background;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The engine operations of the plan view (spec desktop-ui §2), without JavaFX: it starts them on background
 * threads, feeds the {@link PlanViewModel} on the UI thread and tells the view to render it. Every method but the
 * engine's callbacks runs on the UI thread.
 * <p>
 * Every preparation gets its own operation token: a notification (watcher, end of an operation) carrying another
 * token, or the state of another plan, comes from an earlier run and is dropped. The engine stays held by the
 * operation started here until it is really free, whatever the model says (see {@link #isEngineHeld()}).
 *
 * @param <P> the prepared plan ({@code Plan} in the application)
 * @param <E> the accepted execution ({@code Execution} in the application)
 */
public final class PlanOperations<P, E> {

    /** The calls of the engine on the pipeline of the view, its plans and its executions (background threads). */
    public interface Engine<P, E> {
        /** Prepares a plan; {@code started} gets it as soon as it can be stopped, before it is prepared. */
        P prepare(Consumer<PipelineState> watcher, Consumer<P> started, ResumePoint chosen) throws Exception;

        /** The streaming run: the pipeline executed as its files are listed, without plan. */
        E run(Consumer<PipelineState> watcher, ResumePoint chosen) throws Exception;

        E execute(P plan, ResumePoint override) throws Exception;

        /** Analyses the files of the plan selected again; returns once it is PREPARED again. */
        void analyse(P plan) throws Exception;

        /** Returns once the engine has released the execution. */
        void await(E run) throws InterruptedException;

        PipelineState planState(P plan);

        List<WorkItemExecution> orderedItems(P plan);

        Optional<String> executionRefusal(P plan);

        void cancelPreparation(P plan);

        void cancelAnalysis(P plan);

        PipelineState runState(E run);

        void pause(E run);

        void resume(E run);

        void cancel(E run);
    }

    /** What the view does for the operations (UI thread, except {@link #showError}). */
    public interface View {
        /** Renders the model and the buttons. */
        void render();

        /** From any thread. */
        void showError(Throwable failure);

        /** The plan is prepared: the resume junction is brought into view. */
        void scrollToResume();

        /** The planned processing of the one file "Analyse" was asked for, once analysed. */
        void showDetail(WorkItemExecution item);

        /** A save of the editor, applied once the engine is free (see {@link #editorSaved}). */
        void applySaved(Path saved);
    }

    private final Engine<P, E> engine;
    private final Executor background;
    private final Executor ui;
    private final View view;

    private PlanViewModel model = new PlanViewModel();
    /** The token of the current preparation and of the execution of its plan. */
    private Object operation = new Object();
    private P plan;
    /** The plan being prepared, until {@code prepare()} returns: the targets of the items analysed so far. */
    private P preparingPlan;
    /** The execution of the plan, completed once the engine accepted it (null before the copy). */
    private CompletableFuture<E> execution;
    /**
     * The engine is still held by an operation started here: from Prepare until {@code prepare()} has
     * returned, from Copy until the execution is really over ({@code await()}). The model may
     * already be PREPARED or FINISHED (terminal watcher notification) while the engine is not free yet.
     */
    private boolean engineHeld;
    /** The token of the operation holding the engine: only its own end releases it (not renewed by a reload). */
    private Object holdToken;
    /** A save of the editor received while the engine was held: applied once it is free. */
    private Path pendingSaved;
    /** The last state notified for the current operation: its registry gives the resources in use. */
    private PipelineState liveState;
    /** The file "Analyse" was asked for alone: its planned processing opens once analysed. */
    private WorkItemExecution detailAfterAnalysis;

    /**
     * @param background the threads of the engine calls
     * @param ui         the UI thread: the results, the watcher notifications
     */
    public PlanOperations(Engine<P, E> engine, Executor background, Executor ui, View view) {
        this.engine = engine;
        this.background = background;
        this.ui = ui;
        this.view = view;
    }

    public PlanViewModel model() {
        return model;
    }

    /** The prepared plan, null before (and while preparing). */
    public P plan() {
        return plan;
    }

    /** The prepared plan, or while preparing the plan being prepared (its targets fill in as items are analysed). */
    public P targetsPlan() {
        return plan != null ? plan : preparingPlan;
    }

    /** A preparation is stopped through its plan, handed out once the pipeline file is read. */
    public boolean isPreparationStoppable() {
        return preparingPlan != null;
    }

    public PipelineState liveState() {
        return liveState;
    }

    /** See {@link #engineHeld}. */
    public boolean isEngineHeld() {
        return engineHeld;
    }

    /** A preparation or an execution holds the engine, or the model says one is running. */
    public boolean busy() {
        return engineHeld || model.isActive();
    }

    /**
     * The pipeline is read again: the prepared plan, if any, is dropped (a late notification of it is ignored) and
     * the model starts afresh, keeping the resume point chosen by the user. Only while the engine is idle.
     */
    public void dropPlan(Filter filter) {
        operation = new Object();
        plan = null;
        preparingPlan = null;
        execution = null;
        ResumePoint chosen = model.chosenPoint();
        model = new PlanViewModel();
        model.setChosenPoint(chosen);
        model.setFilter(filter);
    }

    /** Renders, then applies an editor save received while the engine was held, once it is free. */
    public void refresh() {
        view.render();
        if (!busy() && pendingSaved != null) {
            Path saved = pendingSaved;
            pendingSaved = null;
            view.applySaved(saved); // last: it rebuilds the view (or replaces it)
        }
    }

    /** A save of the editor: applied at once, or once the engine is free. */
    public void editorSaved(Path saved) {
        if (busy()) {
            pendingSaved = saved;
        } else {
            view.applySaved(saved);
        }
    }

    private Object hold() {
        Object hold = new Object();
        holdToken = hold;
        engineHeld = true;
        return hold;
    }

    /** The operation of this hold has really ended in the engine (the caller refreshes). */
    private void release(Object hold) {
        if (hold == holdToken) {
            engineHeld = false;
            holdToken = null;
        }
    }

    private <T> void run(Callable<T> work, Consumer<? super T> onSuccess, Consumer<? super Throwable> onError) {
        Background.run(background, ui, work, onSuccess, onError);
    }

    private List<WorkItemExecution> ordered() {
        return plan == null ? List.of() : engine.orderedItems(plan);
    }

    /**
     * The button of the mode (spec execution-mode §5): "Prepare the plan" (plan), "Prepare and copy" (auto: the copy
     * starts once prepared), "Copy as files are listed" (streaming: no plan, see {@link #startStreaming}).
     */
    public void prepare() {
        if (busy() || !model.canPrepare()) {
            return;
        }
        if (model.executionMode() == ExecutionMode.STREAMING) {
            startStreaming();
            return;
        }
        Object op = new Object();
        operation = op;
        Object hold = hold();
        plan = null;
        preparingPlan = null;
        execution = null;
        model.startPreparing();
        ResumePoint chosen = model.chosenPoint(); // like a cursor at that point (spec manual-point §1)
        refresh();
        run(() -> engine.prepare(state -> onState(op, state),
                        started -> ui.execute(() -> onPrepareStarted(op, started)), chosen),
                prepared -> onPrepared(op, hold, prepared),
                // missing or invalid pipeline file, engine busy or closed, an Error, the application closing
                failure -> onPrepareRefused(op, hold, failure));
    }

    /** The watcher notifications that follow refresh the table, so its targets fill in. */
    private void onPrepareStarted(Object op, P started) {
        if (op == operation && plan == null) {
            preparingPlan = started;
            refresh(); // Stop can now stop it
        }
    }

    private void onPrepareRefused(Object op, Object hold, Throwable failure) {
        release(hold); // prepare() has returned (thrown)
        if (op == operation) {
            preparingPlan = null;
            execution = null; // a refused streaming run
            model.reset();
        }
        refresh();
        view.showError(failure);
    }

    /**
     * The streaming run ({@code "execution": "streaming"}, spec execution-mode §5): the engine runs the pipeline
     * directly, each file processed as soon as it is listed; the watcher feeds the table as for a copy, Pause and
     * Stop act on it. No plan: no resume point to change, no target shown. A refused run (invalid pipeline, engine
     * busy) brings the view back to "not prepared".
     */
    private void startStreaming() {
        Object op = new Object();
        operation = op;
        Object hold = hold();
        plan = null;
        preparingPlan = null;
        model.startStreaming();
        ResumePoint chosen = model.chosenPoint();
        CompletableFuture<E> future = new CompletableFuture<>();
        execution = future;
        refresh();
        run(() -> {
                    E run = engine.run(state -> onState(op, state), chosen);
                    future.complete(run);
                    return run;
                },
                run -> awaitThenRelease(op, hold, future, run),
                failure -> { // missing or invalid pipeline file, engine busy or closed, an Error, the application closing
                    future.completeExceptionally(failure);
                    onPrepareRefused(op, hold, failure);
                });
    }

    /** Watcher notifications (background thread, coalesced ~10 Hz) of the preparation and of the execution. */
    private void onState(Object op, PipelineState state) {
        ui.execute(() -> {
            if (op != operation || (plan != null && state != engine.planState(plan))) {
                return; // an earlier run: it must not change the phase
            }
            liveState = state;
            model.update(state, ordered());
            afterUpdate();
        });
    }

    private void onPrepared(Object op, Object hold, P prepared) {
        release(hold); // prepare() has returned: the engine is free
        if (op != operation) {
            refresh();
            return;
        }
        plan = prepared;
        preparingPlan = null;
        model.setExecutionRefusal(engine.executionRefusal(prepared));
        model.update(engine.planState(prepared), engine.orderedItems(prepared));
        afterUpdate();
        view.scrollToResume();
    }

    /**
     * Renders, then starts the copy when the plan just became ready and "automatic execution" is checked.
     * Checked after every update, but only once the plan is known: the terminal watcher notification of
     * the preparation may come before {@code prepare} returns.
     */
    private void afterUpdate() {
        refresh();
        if (plan != null && model.consumeAutoExecute()) {
            copy();
        }
    }

    /** Only from PREPARED; when the engine refuses the plan the view goes back to the prepared plan. */
    public void copy() {
        if (plan == null || engineHeld || model.phase() != Phase.PREPARED || !model.canCopy()) {
            return;
        }
        Object op = operation;
        Object hold = hold();
        P toRun = plan;
        ResumePoint override = model.override();
        model.startExecuting(); // before execute(): its first notifications must find the model executing
        CompletableFuture<E> future = new CompletableFuture<>();
        execution = future;
        refresh();
        run(() -> {
                    E run = engine.execute(toRun, override); // the preparation's watcher goes on
                    future.complete(run);
                    return run;
                },
                run -> awaitThenRelease(op, hold, future, run),
                failure -> { // refused (engine busy or closed), an Error, the application closing
                    future.completeExceptionally(failure);
                    onExecuteRefused(op, hold, future, toRun, override, failure);
                });
    }

    /** Once a copy or a streaming run is accepted: waits in the background for its real end, then frees the view. */
    private void awaitThenRelease(Object op, Object hold, CompletableFuture<E> future, E run) {
        run(() -> {
                    engine.await(run); // returns once the engine has released the operation
                    return run;
                },
                done -> released(op, hold, future, run, null),
                // interrupted: the application is closing, released anyway
                failure -> released(op, hold, future, run, failure instanceof InterruptedException ? null : failure));
    }

    /** Never leaves the view held, whatever happened. */
    private void released(Object op, Object hold, CompletableFuture<E> future, E run, Throwable failure) {
        release(hold);
        if (failure != null && op == operation && future == execution) {
            model.update(engine.runState(run), ordered());
            if (model.isActive()) { // no terminal state will come: the plan is dropped
                model.reset();
                plan = null;
                execution = null;
            }
        }
        refresh();
        if (failure != null) {
            view.showError(failure);
        }
    }

    private void onExecuteRefused(Object op, Object hold, CompletableFuture<E> future, P toRun,
                                  ResumePoint override, Throwable e) {
        release(hold);
        if (op == operation && future == execution) {
            // a fresh model on the same plan: the phase follows the plan's state again (PREPARED, not locked)
            PlanViewModel back = new PlanViewModel();
            back.setExecutionMode(model.executionMode());
            back.setAutoExecute(model.isAutoExecute());
            back.setFilter(model.filter());
            back.setOverride(override);
            back.update(engine.planState(toRun), engine.orderedItems(toRun));
            model = back;
            execution = null;
        }
        refresh();
        view.showError(e);
    }

    public void pause() {
        control(engine::pause);
    }

    public void resume() {
        control(engine::resume);
    }

    /** Stops the preparation, the analysis or the execution running. */
    public void stop() {
        if (model.phase() == Phase.PREPARING) {
            if (preparingPlan != null) {
                // non-blocking: the end of prepare() shows what was listed and analysed so far
                engine.cancelPreparation(preparingPlan);
            }
            return;
        }
        if (model.phase() == Phase.ANALYSING && plan != null) {
            engine.cancelAnalysis(plan); // non-blocking: the end of analyse() brings the view back to the plan
            return;
        }
        control(engine::cancel);
    }

    /**
     * Pause, resume or stop, in the background once the engine accepted the execution; a pause or resume
     * changes the status without a watcher notification, hence the update afterwards.
     */
    private void control(Consumer<E> action) {
        CompletableFuture<E> future = execution;
        if (future == null || !model.isExecutionActive()) {
            return;
        }
        Object op = operation;
        future.thenAcceptAsync(running -> {
            action.accept(running);
            ui.execute(() -> {
                if (op == operation && future == execution) {
                    model.update(engine.runState(running), ordered());
                    refresh();
                }
            });
        }, background).exceptionally(failure -> {
            if (!future.isCompletedExceptionally()) { // a refused execute() is already reported by onExecuteRefused
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                view.showError(cause);
            }
            return null;
        });
    }

    /**
     * The files a manual resume point selected again although the preparation skipped them at the listing are analysed
     * in the background, like a preparation (spec deferred-analysis §2): their targets fill in, Copy waits for the
     * end, Stop ends it early (the files left are then analysed by the copy). The resume point cannot be changed
     * meanwhile: stop the analysis first.
     *
     * @param single the one file "Analyse" was asked for, its planned processing shown once analysed; else null
     */
    public void analyse(List<WorkItemExecution> toAnalyse, WorkItemExecution single) {
        if (engineHeld) {
            return; // not expected: the plan is prepared and idle; the copy analyses them anyway
        }
        detailAfterAnalysis = single;
        Object op = operation;
        Object hold = hold();
        P analysed = plan;
        model.startAnalysing(toAnalyse);
        refresh();
        run(() -> {
                    engine.analyse(analysed); // the preparation's watcher reports its progress
                    return null;
                },
                done -> onAnalysed(op, hold, analysed, null),
                // refused (engine busy or closed), an unexpected failure, the application closing
                failure -> onAnalysed(op, hold, analysed, failure));
    }

    /** analyse() has returned: the plan is PREPARED again, the view follows it. */
    private void onAnalysed(Object op, Object hold, P analysed, Throwable failure) {
        release(hold);
        if (op == operation && plan == analysed) {
            model.update(engine.planState(analysed), engine.orderedItems(analysed));
        }
        refresh();
        WorkItemExecution single = detailAfterAnalysis;
        detailAfterAnalysis = null;
        if (failure == null && single != null && op == operation && plan == analysed && single.getProjection() != null) {
            view.showDetail(single); // one file asked for: what it was analysed for
        }
        if (failure != null) {
            view.showError(failure);
        }
    }
}
