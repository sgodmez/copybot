package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeResolver;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs one pipeline: one virtual thread per listing and per work item,
 * concurrency bounded by the ResourceRegistry (see the design spec).
 *
 * <p>Completion is tracked with a plain counter ({@code pendingTasks}) guarded by
 * {@code completionLock}, not a {@link java.util.concurrent.Phaser}: a Phaser's registered-parties
 * count is capped at 65534, and in two-phase mode every listed item registers before any of them
 * can deregister (they all park on {@code listingGate.await()}), so a single listing beyond that
 * size would overflow it. Large listings (100k+ files) are an explicit use case of this project.
 */
public class MainExecutor implements Runnable {

    /** Minimum delay between two watcher notifications (coalescing, see {@link #notifyWatcher()}). */
    static final long WATCHER_PERIOD_MILLIS = 100;

    /** Best-effort grace period given to in-flight tasks to unwind after a shutdown request. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private final PipelineConfig pipelineConfig;
    private final Consumer<PipelineState> watcher;
    private final ResourceRegistry registry;
    private final PipelineState state;

    /**
     * Unique per pipeline run (prepare and execute share it), handed to the out step: it names its temporary
     * files and recognises the ones a crashed run left behind (spec safe-write §3).
     */
    private final WriteContext writeContext = WriteContext.newRun();

    private List<PipelineStep<IInAction>> inSteps;
    private List<PipelineStep<?>> itemSteps;
    private boolean startProcessingWhileListing;

    /** null: single-phase run without barrier nor resume (historical behaviour of run()). */
    private final ResumeContext resume;

    /** Number of item steps before the preparation barrier (the analyse steps). */
    private int barrierIndex;

    /** Exclusive end of the steps run by the items the listings emit: the barrier while preparing, all steps otherwise. */
    private volatile int phaseEnd;

    /**
     * False while preparing: an item then ends PENDING even when no step follows the barrier, so that the
     * resume point decides whether it is selected (an item ended DONE would escape the resume point).
     */
    private volatile boolean finalPhase;

    /** Set once the configuration warnings are published: prepare() collects them, execute() keeps them. */
    private boolean warningsCollected;

    private ResumeResolver resolver;
    private ResumeProposal proposal;
    private List<WorkItemExecution> orderedItems = List.of();

    private ExecutorService taskExecutor;
    private CountDownLatch listingGate;
    private final AtomicBoolean listingFailed = new AtomicBoolean(false);

    private final Object completionLock = new Object();
    private long pendingTasks;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object watcherLock = new Object();
    private volatile Thread notifier;

    /** Guards phaseThread, cancelInterruptSent and paused against the start and the end of a phase. */
    private final Object phaseLock = new Object();
    /** The thread running the current phase (single-phase run, prepare or execute), null between phases
     * and once the phase passed its point of no return (see {@link #commitPhase()}). */
    private Thread phaseThread;
    /** True when cancel() interrupted the phase thread: that interrupt must not leak to the caller. */
    private boolean cancelInterruptSent;
    private boolean paused;
    private volatile boolean cancelRequested;

    /**
     * @param watcher optional progress observer. Its periodic notifications are invoked from a
     *                <em>background thread</em>, at most once every {@value #WATCHER_PERIOD_MILLIS} ms
     *                (notifications are coalesced: the observer always sees the latest state, not every
     *                single transition), plus one terminal, guaranteed notification per phase: once for a
     *                run without resume, once after prepare() and once after execute() otherwise. That
     *                terminal notification is delivered on the thread running the phase: for
     *                {@link #prepare()}, the caller's thread. Exceptions it throws are swallowed. The
     *                {@link PipelineState} handed over is the live, mutating state: read it, do not
     *                retain it. The watcher must not call close() nor any other blocking engine operation
     *                (the phase notifying it would wait for itself).
     */
    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry,
                        ResumeContext resume) {
        this.pipelineConfig = pipelineConfig;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    // visible for tests: runs with pre-resolved steps, bypassing PluginEngine, without barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this(inSteps, itemSteps, itemSteps.size(), startProcessingWhileListing, watcher, registry, null);
    }

    // visible for tests: pre-resolved steps, the first barrierIndex item steps run before the barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps, int barrierIndex,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry,
                 ResumeContext resume) {
        this.pipelineConfig = null;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.barrierIndex = barrierIndex;
        this.startProcessingWhileListing = startProcessingWhileListing;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    /**
     * Without resume: one phase, every step, as before. With resume: {@link #prepare()} then, when the
     * preparation succeeded, {@link #execute(ResumePoint)} with the proposed resume point.
     */
    @Override
    public void run() {
        if (resume == null) {
            runSinglePhase();
            return;
        }
        prepare();
        if (state.getStatus() == PipelineStatus.PREPARED) {
            execute(null);
        }
    }

    /**
     * Stops the pipeline cleanly: lifts a pause (the steps it lets through do not start, the listings emit
     * nothing more), then interrupts the current phase, whose listings and items are interrupted in turn,
     * release their permits and go back to PENDING. The pipeline ends CANCELLED and the resume
     * cursor is not written. A phase that has not started yet ends CANCELLED as soon as it starts; a phase
     * already past its point of no return (all its work done, publishing its outcome) is not affected.
     * Idempotent; no effect once the pipeline has terminated. Callable from any thread.
     */
    public void cancel() {
        synchronized (phaseLock) {
            if (cancelRequested || isTerminated()) {
                return;
            }
            cancelRequested = true;
            liftPause();
            if (phaseThread != null) {
                cancelInterruptSent = true;
                phaseThread.interrupt();
            }
        }
    }

    /**
     * Stops granting resources: no new step starts and the listings wait before emitting their next item;
     * the steps already running finish normally. Status PAUSED until {@link #resume()}. No effect once
     * the pipeline has terminated or is being cancelled. Callable from any thread.
     */
    public void pause() {
        synchronized (phaseLock) {
            if (paused || cancelRequested || isTerminated()) {
                return;
            }
            paused = true;
            registry.pause();
            state.compareAndSetStatus(PipelineStatus.RUNNING, PipelineStatus.PAUSED);
        }
    }

    /** Lifts a {@link #pause()}: back to RUNNING, the waiting steps and listings go on. */
    public void resume() {
        synchronized (phaseLock) {
            liftPause();
        }
    }

    /** Caller holds phaseLock. */
    private void liftPause() {
        if (!paused) {
            return;
        }
        paused = false;
        registry.resume();
        state.compareAndSetStatus(PipelineStatus.PAUSED, PipelineStatus.RUNNING);
    }

    /** Status at the start of a phase: RUNNING, or PAUSED when a pause was requested beforehand. */
    private void markRunning() {
        synchronized (phaseLock) {
            state.setStatus(paused ? PipelineStatus.PAUSED : PipelineStatus.RUNNING);
        }
    }

    /** Turns a cancel() requested before the phase started into the phase's cancellation path. */
    private void checkCancelled() throws InterruptedException {
        if (cancelRequested) {
            throw new InterruptedException("cancelled");
        }
    }

    /**
     * Point of no return of a phase, once all its work is done: a cancel() requested until now cancels
     * the phase, and so does an external interrupt still pending. A later cancel() no longer interrupts it
     * (it would hit the resume cursor write or the state file read): the phase publishes its outcome,
     * except that a failure of the phase from then on ends CANCELLED rather than ERROR, with its cause kept
     * (see {@link #onPhaseFailed}). A phase started afterwards is cancelled as soon as it starts.
     */
    private void commitPhase() throws InterruptedException {
        synchronized (phaseLock) {
            // an external interrupt still pending (e.g. nothing was left to wait for) cancels the phase too:
            // it must never reach the resume cursor write or the state file read
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted");
            }
            checkCancelled();
            phaseThread = null;
        }
    }

    private boolean isTerminated() {
        PipelineStatus status = state.getStatus();
        return status == PipelineStatus.SUCCESS || status == PipelineStatus.ERROR || status == PipelineStatus.CANCELLED;
    }

    /**
     * Cancellation (cancel() or an interrupt of the phase thread): unblock every task still parked in
     * acquireAll/IO and let it release its permits, THEN restore an external interrupt (awaitTermination
     * would return immediately with the flag set, leaving writers to be killed mid-stream by JVM exit).
     * The interrupt sent by cancel() is not restored: it must not leak to the caller (e.g. a UI worker).
     */
    private void onPhaseInterrupted() {
        boolean byCancel;
        synchronized (phaseLock) { // cancel() interrupts under this lock: its interrupt is already delivered
            byCancel = cancelRequested;
            // a later cancel() must not interrupt again: it would cut the grace period of shutdownTasks()
            // short, or wipe (in endPhase) the external interrupt restored below
            phaseThread = null;
        }
        if (byCancel) {
            Thread.interrupted();
        }
        state.setStatus(PipelineStatus.CANCELLED);
        shutdownTasks();
        if (!byCancel) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A pipeline failure (e.g. a missing plugin/action, an invalid state file): reported in the state,
     * never thrown out of the pipeline thread (spec engine-instance §2), the cause set before the status.
     * A failure while a cancel() is pending is taken as a consequence of the cancellation: the phase ends
     * CANCELLED, its cause still kept in the state.
     *
     * @param duringPreparation the failure happened while preparing (see {@link PipelineState#isPreparationFailed()})
     */
    private void onPhaseFailed(Throwable failure, boolean duringPreparation) {
        if (cancelRequested) {
            state.recordFailureIfAbsent(failure); // the first cause wins; before the status, like below
            state.setStatus(PipelineStatus.CANCELLED);
            return;
        }
        state.setFailure(failure);
        state.setPreparationFailed(duringPreparation); // before the status, like the cause
        state.setStatus(PipelineStatus.ERROR);
    }

    private void runSinglePhase() {
        markRunning();
        state.setListingInProgress(true);
        startPhase();
        boolean preparing = true;
        try {
            checkCancelled();
            // Step resolution is INSIDE the try: a missing plugin/action must be reported as a
            // failed run (status ERROR + watcher notified), not as an exception out of a state-less run.
            // It is this run's preparation: its failure is a preparation failure.
            resolveStepsIfNeeded();
            preparing = false;
            phaseEnd = itemSteps.size();
            finalPhase = true;
            runListings();
            commitPhase();
            state.setStatus(resolveFinalStatus());
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, preparing);
        } finally {
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /**
     * Lists and analyses every item, then resolves the resume point: items end PENDING (selected),
     * SKIPPED (with a reason) or ERROR, and the pipeline PREPARED. Nothing after the analyses runs.
     * A listing failure leaves the pipeline in ERROR: a partial listing would give a wrong resume point.
     * Never throws for a pipeline failure (unresolvable step, invalid state file, "destination" mode
     * without target paths...): the pipeline ends ERROR with the cause in {@link PipelineState#getFailure()}
     * and {@link PipelineState#isPreparationFailed()} set (not for a listing failure).
     * Cancellable ({@link #cancel()}): it then ends CANCELLED.
     */
    public void prepare() {
        if (resume == null) {
            throw new IllegalStateException("prepare() requires a resume context");
        }
        markRunning();
        state.setListingInProgress(true);
        startPhase();
        try {
            checkCancelled();
            resolveStepsIfNeeded();
            phaseEnd = barrierIndex;
            finalPhase = false;
            runListings();
            commitPhase();
            if (listingFailed.get()) {
                state.setStatus(PipelineStatus.ERROR);
                return;
            }
            orderedItems = ResumeResolver.order(state.getWorkItems());
            resolver = new ResumeResolver(resume.mode(), resume.store(), findOutAction());
            proposal = resolver.propose(orderedItems);
            state.setResumeProposal(proposal);
            resolver.apply(proposal.point(), proposal.source(), orderedItems);
            projectItems();
            state.setStatus(PipelineStatus.PREPARED);
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, true);
        } finally {
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /** The process steps among the item steps (the instances the execution will use). */
    @SuppressWarnings("unchecked")
    private List<PipelineStep<IProcessAction>> processSteps() {
        List<PipelineStep<IProcessAction>> steps = new ArrayList<>();
        for (PipelineStep<?> step : itemSteps) {
            if (step.getAction() instanceof IProcessAction) {
                steps.add((PipelineStep<IProcessAction>) step);
            }
        }
        return steps;
    }

    /**
     * The dry run of the process steps for every item prepared without error, selected or not, so that a manual
     * resume point chosen later needs nothing more (spec pattern-helper §4.3). A failing dry run is shown, never
     * an item error. The dry run uses the same action instances as the execution: dryRun must not change their state.
     */
    private void projectItems() throws InterruptedException {
        List<PipelineStep<IProcessAction>> steps = processSteps();
        for (WorkItemExecution exec : orderedItems) {
            checkCancelled();
            if (exec.getStatus() != ItemStatus.ERROR) {
                exec.setProjection(DryRunner.project(exec.getWorkItem(), steps));
            }
        }
    }

    /** Applies a manual resume point to the item statuses (also used by the dry-run preview). */
    void applyOverride(ResumePoint override) {
        if (override != null) {
            resolver.apply(override, ResumeSource.MANUAL, orderedItems);
        }
    }

    /**
     * Runs the steps after the barrier for the selected items, then advances the resume cursor
     * (only when the run completed normally and the mode is not NONE).
     *
     * @param override resume point chosen by the user, null for the proposed one
     */
    public void execute(ResumePoint override) {
        if (state.getStatus() != PipelineStatus.PREPARED) {
            throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", state.getStatus()));
        }
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        applyOverride(override);
        markRunning();
        startPhase();
        try {
            checkCancelled();
            finalPhase = true;
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    submitItem(exec, barrierIndex, itemSteps.size());
                }
            }
            awaitCompletion();
            commitPhase(); // a cancelled run never writes the cursor, a later cancel never interrupts its write
            // The final status is published once, after the cursor save: a cursor write failure
            // must never be seen by watchers as SUCCESS followed by ERROR.
            PipelineStatus finalStatus = resolveFinalStatus();
            if (!saveCursor(point, source)) {
                finalStatus = PipelineStatus.ERROR;
            }
            state.setStatus(finalStatus);
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, false);
        } finally {
            endPhase();
        }
    }

    /** @return false when the cursor could not be written */
    private boolean saveCursor(ResumePoint point, ResumeSource source) {
        if (resume.mode() == ResumeMode.NONE) {
            return true;
        }
        try {
            resolver.nextCursor(orderedItems, point, source).ifPresent(resume.store()::writeCursor);
            return true;
        } catch (RuntimeException e) {
            // the files are copied, but the next resume would not know it: this run is not a success
            state.setFailure(e);
            return false;
        }
    }

    /** Publishes the configuration warnings of every step, listings included, once (spec safe-write §6). */
    private void collectConfigWarnings() {
        if (warningsCollected) {
            return;
        }
        List<String> warnings = new ArrayList<>();
        inSteps.forEach(step -> addWarnings(warnings, step.getAction()));
        itemSteps.forEach(step -> addWarnings(warnings, step.getAction()));
        state.setWarnings(warnings);
        warningsCollected = true;
    }

    private static void addWarnings(List<String> warnings, IAction action) {
        List<String> actionWarnings = action.configWarnings();
        if (actionWarnings != null) { // a plugin returning null has nothing to say
            warnings.addAll(actionWarnings);
        }
    }

    private void resolveStepsIfNeeded() {
        if (pipelineConfig != null && inSteps == null) {
            inSteps = StepResolver.in(pipelineConfig);
            itemSteps = StepResolver.itemSteps(pipelineConfig);
            barrierIndex = pipelineConfig.analyseSteps() == null ? 0 : pipelineConfig.analyseSteps().size();
            startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
        }
        registerStepCapacities();
        collectConfigWarnings();
    }

    /** Submits every listing and waits for them AND every item they emitted (including forked ones). */
    private void runListings() throws InterruptedException {
        listingGate = new CountDownLatch(inSteps.size());
        for (PipelineStep<IInAction> inStep : inSteps) {
            submitTask(() -> runListing(inStep));
        }
        awaitCompletion();
    }

    private void startPhase() {
        synchronized (phaseLock) {
            phaseThread = Thread.currentThread(); // what cancel() interrupts
        }
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startNotifier();
        notifyWatcher();
    }

    private void endPhase() {
        boolean clearCancelInterrupt;
        synchronized (phaseLock) {
            phaseThread = null;
            clearCancelInterrupt = cancelInterruptSent;
            cancelInterruptSent = false;
            if (isTerminated()) {
                liftPause(); // a pause never outlives the run
            }
        }
        if (clearCancelInterrupt) {
            // the interrupt came from cancel(): it must not leak to the caller of prepare() (e.g. a UI worker)
            Thread.interrupted();
        }
        shutdownTasks();
        stopNotifier(); // includes the final, guaranteed notification
    }

    /** The out step's action, null when the pipeline has none or its steps are not resolved (yet). */
    IOutAction findOutAction() {
        if (itemSteps != null && !itemSteps.isEmpty() && itemSteps.getLast().getAction() instanceof IOutAction out) {
            return out;
        }
        return null;
    }

    ResumeProposal getProposal() {
        return proposal;
    }

    List<WorkItemExecution> getOrderedItems() {
        return orderedItems;
    }

    /**
     * Publishes the capacity of the implicit {@code step:<n>} resource that backs
     * {@code maxConcurrency} (design spec §4.3). Without this the name would be unknown to
     * {@code ResourceSettings} and fall back to the default capacity of 1, turning
     * {@code "maxConcurrency": 8} into a full serialization of the step.
     */
    private void registerStepCapacities() {
        for (int i = 0; i < itemSteps.size(); i++) {
            PipelineStepConfig config = itemSteps.get(i).getConfig();
            if (config != null && config.maxConcurrency() != null) {
                registry.registerCapacity("step:" + i, config.maxConcurrency());
            }
        }
    }

    /** A run is a success only if the listings AND every item succeeded. */
    private PipelineStatus resolveFinalStatus() {
        boolean anyItemFailed = state.getWorkItems().stream()
                .anyMatch(item -> item.getStatus() == ItemStatus.ERROR);
        return listingFailed.get() || anyItemFailed ? PipelineStatus.ERROR : PipelineStatus.SUCCESS;
    }

    /**
     * Interrupts whatever is still running ({@code shutdownNow}, not {@code shutdown}: threads parked
     * in {@code acquireAll} would never wake up otherwise) and waits briefly for the tasks to unwind,
     * so their {@code finally} blocks release their permits and close their streams before the caller
     * (possibly the JVM) moves on. Idempotent and harmless once everything has completed.
     */
    private void shutdownTasks() {
        if (taskExecutor == null) {
            return;
        }
        taskExecutor.shutdownNow();
        if (taskExecutor.isTerminated()) {
            return;
        }
        try {
            taskExecutor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Registers one pending task, then submits it to the executor. Registration happens
     * BEFORE submission (same discipline for every caller), so the pending count never drops
     * to zero prematurely; if the submission itself throws (e.g. RejectedExecutionException),
     * the registration is rolled back so {@link #awaitCompletion()} never hangs on a task that
     * was never actually queued.
     */
    private void submitTask(Runnable task) {
        synchronized (completionLock) {
            pendingTasks++;
        }
        try {
            taskExecutor.submit(() -> {
                try {
                    task.run();
                } finally {
                    completeTask();
                }
            });
        } catch (RuntimeException e) {
            completeTask();
            throw e;
        }
    }

    private void completeTask() {
        synchronized (completionLock) {
            pendingTasks--;
            if (pendingTasks == 0) {
                completionLock.notifyAll();
            }
        }
    }

    private void awaitCompletion() throws InterruptedException {
        synchronized (completionLock) {
            while (pendingTasks > 0) {
                completionLock.wait();
            }
        }
    }

    private void runListing(PipelineStep<IInAction> inStep) {
        try {
            Set<String> footprint = FootprintResolver.forListing(inStep.getAction(), inStep.getConfig());
            registry.acquireAll(footprint);
            try {
                inStep.getAction().listFiles(this::emitItem);
            } finally {
                registry.releaseAll(footprint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (PhaseStopped e) {
            // the phase is being stopped (see emitItem): not a listing failure. Only this private marker is
            // ignored: any other exception, a plugin's own CancellationException included, fails the run.
        } catch (Throwable t) {
            // A failing listing must not silently look like success: the whole run is marked ERROR.
            state.recordFailureIfAbsent(t); // the first listing failure wins, atomically
            listingFailed.set(true);
        } finally {
            listingGate.countDown();
            if (listingGate.getCount() == 0) {
                state.setListingInProgress(false);
                notifyWatcher();
            }
        }
    }

    /**
     * The phase is being stopped (cancel, interrupt): thrown by emitItem through the plugin's listFiles,
     * and by runStep when the fork of an item is rejected by the executor the phase shut down.
     */
    private static final class PhaseStopped extends RuntimeException {
        PhaseStopped() {
            super("phase stopped", null, false, false);
        }
    }

    private void emitItem(WorkItem workItem) {
        // The listing holds its disk for its whole duration, so the registry pause alone would not
        // freeze it: wait here, before creating the item.
        try {
            registry.awaitNotPaused();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelRequested || taskExecutor.isShutdown()) {
                throw new PhaseStopped();
            }
            // not an interrupt of ours: the listing stops short, which must fail the run
            throw new IllegalStateException("listing interrupted", e);
        }
        if (cancelRequested) {
            throw new PhaseStopped(); // woken by the pause cancel() lifted: emit nothing more
        }
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
        state.getWorkItems().add(exec);
        notifyWatcher();
        try {
            submitItem(exec, 0, phaseEnd);
        } catch (RejectedExecutionException e) {
            if (taskExecutor.isShutdown()) {
                // the phase shut its executor down (cancel, interrupt) while this listing was emitting
                throw new PhaseStopped();
            }
            throw e;
        }
    }

    private void submitItem(WorkItemExecution exec, int fromStep, int toStep) {
        submitTask(() -> runItem(exec, fromStep, toStep));
    }

    /**
     * Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING, an item the
     * out step skipped ends SKIPPED with its reason.
     */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            StepOutcome outcome = StepOutcome.CONTINUE;
            for (int i = fromStep; i < toStep; i++) {
                PipelineStep<?> step = itemSteps.get(i);
                Set<String> footprint = FootprintResolver.resolve(step.getAction(), exec.getWorkItem(), step.getConfig(), i);
                exec.setWaitingResources(i, footprint);
                notifyWatcher();
                registry.acquireAll(footprint);
                if (cancelRequested) {
                    // cancel() lifts a pause before the tasks are shut down: the waiters it grants must not
                    // start their step
                    registry.releaseAll(footprint);
                    throw new InterruptedException("cancelled");
                }
                exec.setRunning(i);
                notifyWatcher();
                try {
                    outcome = runStep(exec, step, i, toStep);
                } finally {
                    registry.releaseAll(footprint);
                }
                if (!outcome.continueItem()) {
                    break;
                }
            }
            if (outcome.skipReason() != null) {
                exec.setSkipped(outcome.skipReason());
            } else if (!outcome.continueItem() || (toStep == itemSteps.size() && finalPhase)) {
                exec.setDone();
            } else {
                exec.setReady();
                if (!finalPhase) {
                    exec.markPrepared(); // stopped at the preparation barrier: analysed
                }
            }
        } catch (InterruptedException e) {
            exec.setReady(); // not left WAITING_RESOURCES / RUNNING: the item is simply not done
            Thread.currentThread().interrupt();
        } catch (PhaseStopped e) {
            // a fork rejected because the phase is being stopped: an interrupt, not an item failure
            exec.setReady();
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (cancelRequested) {
                // a failure caused by the cancel (e.g. a ClosedByInterruptException wrapped by the step) is an
                // interruption like the ones above, not an item error
                // the cause (t) is intentionally dropped: the item is an interruption and the run ends CANCELLED
                exec.setReady();
            } else {
                exec.setError(t);
                exec.propagateFailureToAncestors(); // a failed fork holds the resume cursor back before its parent
                if (!finalPhase) {
                    exec.markPrepared(); // failed before the barrier: nothing more to prepare for it
                }
            }
        } finally {
            notifyWatcher();
        }
    }

    /**
     * How an item goes on after one step.
     *
     * @param continueItem false when the item stops here
     * @param skipReason   non null when the out step skipped the item: it ends SKIPPED with this reason
     */
    private record StepOutcome(boolean continueItem, String skipReason) {
        static final StepOutcome CONTINUE = new StepOutcome(true, null);
        /** Filtered out by a process step: the item ends DONE. */
        static final StepOutcome FILTERED = new StepOutcome(false, null);

        static StepOutcome skipped(String reason) {
            return new StepOutcome(false, reason);
        }
    }

    /**
     * @param toStep exclusive end of the current phase: a forked item stops at the same step as its parent
     */
    private StepOutcome runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep) {
        IAction action = step.getAction();
        WorkItem item = exec.getWorkItem();
        if (action instanceof IAnalyzeAction analyze) {
            analyze.doAnalyze(item);
            return StepOutcome.CONTINUE;
        }
        if (action instanceof IProcessAction process) {
            List<WorkItem> produced = process.doProcess(item);
            if (produced == null || produced.isEmpty()) {
                return StepOutcome.FILTERED;
            }
            exec.replaceWorkItem(produced.get(0));
            for (int i = 1; i < produced.size(); i++) {
                WorkItemExecution forked = new WorkItemExecution(produced.get(i), itemSteps);
                forked.setParent(exec);
                state.getWorkItems().add(forked);
                notifyWatcher();
                try {
                    submitItem(forked, stepIndex + 1, toStep);
                } catch (RejectedExecutionException e) {
                    if (taskExecutor.isShutdown()) {
                        throw new PhaseStopped(); // the phase shut its executor down (cancel, interrupt)
                    }
                    throw e;
                }
            }
            return StepOutcome.CONTINUE;
        }
        if (action instanceof IOutAction out) {
            WriteResult result = out.write(item, writeContext);
            if (result != null && result.isSkipped()) {
                return StepOutcome.skipped(result.reason()); // the steps after it, if any, do not run
            }
            return StepOutcome.CONTINUE;
        }
        throw new UnsupportedOperationException("Unsupported action type: " + action.getClass());
    }

    /**
     * Marks the state as changed. This fires ~(2*steps+2) times per item from every virtual thread,
     * so it must stay O(1) and lock-free: pushing each transition straight to the watcher melts both
     * consumers (the JavaFX UI rebuilds its whole list per notification, the CLI prints one line).
     * The dedicated notifier thread coalesces the flag into at most one call per
     * {@value #WATCHER_PERIOD_MILLIS} ms.
     */
    private void notifyWatcher() {
        dirty.set(true);
    }

    private void startNotifier() {
        if (watcher == null) {
            return;
        }
        notifier = Thread.ofVirtual().name("copybot-watcher-notifier").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (dirty.compareAndSet(true, false)) {
                        deliverWatcher();
                    }
                    Thread.sleep(WATCHER_PERIOD_MILLIS);
                }
            } catch (InterruptedException e) {
                // normal stop: the terminal notification is done by stopNotifier()
            }
        });
    }

    /** Stops the notifier and delivers the terminal state, which must never be missed. */
    private void stopNotifier() {
        Thread current = notifier;
        notifier = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(WATCHER_PERIOD_MILLIS * 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        dirty.set(false);
        deliverWatcher();
    }

    private void deliverWatcher() {
        if (watcher == null) {
            return;
        }
        synchronized (watcherLock) { // serializes the periodic and the terminal notification
            try {
                watcher.accept(state);
            } catch (RuntimeException e) {
                // An observer must never break the pipeline: a throwing watcher must not leak
                // resource permits or a pending-task registration (see submitTask/completeTask),
                // so we swallow it here and keep going.
            }
        }
    }

    PipelineState getState() {
        return state;
    }

    /** True once {@link #cancel()} took effect (requested before the pipeline terminated). */
    boolean isCancelRequested() {
        return cancelRequested;
    }

    String getRunId() {
        return writeContext.runId();
    }
}
