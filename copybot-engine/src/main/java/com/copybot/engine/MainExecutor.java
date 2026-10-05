package com.copybot.engine;

import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeResolver;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private static final CopybotLogger LOG = CopybotLogger.getLogger(MainExecutor.class);

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
    /** volatile: {@link Plan#projectionOf} reads it from another thread while the plan is being prepared */
    private volatile List<PipelineStep<?>> itemSteps;
    private boolean processWhileListing;

    /**
     * null: single-phase run without barrier nor resume (historical behaviour of run(), and {@link #stream()} without
     * resume).
     */
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

    /**
     * While preparing, the process steps dry-run for each item as soon as it is analysed (null otherwise):
     * the targets show up during the preparation, not only at its end.
     */
    private volatile List<PipelineStep<IProcessAction>> dryRunSteps;
    /** From the start of {@link #prepare()} until it returns, and during {@link #analyseDeferred()}. */
    private volatile boolean preparing;
    /** The skipped items to analyse too ({@link #requestAnalysis}). */
    private final Set<WorkItemExecution> requestedAnalysis = ConcurrentHashMap.newKeySet();
    /** How far the plan checks the existing targets (spec conflict-check §1). */
    private volatile ConflictCheck conflictCheck = ConflictCheck.QUICK;
    /** {@link #analyseDeferred()} runs its phase. Guarded by phaseLock. */
    private boolean analysing;
    /** {@link #prepare()} runs and has not published PREPARED yet: {@link #cancelPreparation()} stops it. Guarded by phaseLock. */
    private boolean preparationStoppable;

    /**
     * While preparing, the resume point known before the listing (see {@link ResumeResolver#listingPoint}): the
     * items it does not select are skipped as soon as they are listed, without analysis. null otherwise.
     */
    private volatile ResumePoint listingPoint;

    /** {@link #stream()}: processing while listing, the resume applied at the listing. */
    private volatile boolean streaming;
    /** While streaming with resume: the keys are frozen at the listing, a file without date is an error there. */
    private volatile boolean resumeAtTheListing;
    /** While streaming with the point NOT_AT_DESTINATION: each file is checked once analysed. */
    private volatile boolean checkEachFile;
    /** While streaming with resume: the listed files (not the forks), for the cursor. */
    private final Queue<WorkItemExecution> listedItems = new ConcurrentLinkedQueue<>();
    private final Object warningsLock = new Object();

    /**
     * While preparing, true when the resume point comes from the destination probe: the items are not analysed at
     * the listing, the probe analyses the ones it checks, then the point the ones it selects.
     */
    private volatile boolean analysisOnDemand;

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
        this.conflictCheck = pipelineConfig.conflictCheckMode();
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    // visible for tests: runs with pre-resolved steps, bypassing PluginEngine, without barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                 boolean processWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this(inSteps, itemSteps, itemSteps.size(), processWhileListing, watcher, registry, null);
    }

    // visible for tests: pre-resolved steps, the first barrierIndex item steps run before the barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps, int barrierIndex,
                 boolean processWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry,
                 ResumeContext resume) {
        this.pipelineConfig = null;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.barrierIndex = barrierIndex;
        this.processWhileListing = processWhileListing;
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
     * The streaming run ({@code "execution": "streaming"}, spec execution-mode §3): one phase, every step, each file
     * processed as soon as it is listed. With resume, the point is known before the listing
     * ({@link ResumeResolver#streamingProposal}): the files before the cursor are skipped at the listing, with a
     * destination-based mode each file is skipped once analysed when it is at the destination; the cursor is written
     * at the end like {@link #execute} does (not after a listing failure: the files not listed would be skipped
     * next time). Never prepared first.
     */
    public void stream() {
        streaming = true;
        processWhileListing = true;
        runSinglePhase();
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
            ResumeProposal streamed = streaming && resume != null ? resumeAtTheListing() : null;
            preparing = false;
            phaseEnd = itemSteps.size();
            finalPhase = true;
            runListings();
            commitPhase();
            // like execute(): the final status is published once, after the cursor save
            PipelineStatus finalStatus = resolveFinalStatus();
            if (streamed != null) {
                orderedItems = ResumeResolver.order(listedItems); // the keys are frozen since the listing
                if (!listingFailed.get() && !saveCursor(streamed.point(), streamed.source())) {
                    finalStatus = PipelineStatus.ERROR;
                }
            }
            state.setStatus(finalStatus);
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, preparing);
        } finally {
            listingPoint = null;
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /**
     * Streaming with resume: the point known before the listing, published for the view (its warnings with the
     * others: the CLI prints them too), applied to each file as it is listed.
     *
     * @throws com.copybot.exception.CopybotException the state file cannot be understood, mode destination without
     *                                                target to probe
     */
    private ResumeProposal resumeAtTheListing() {
        resolver = new ResumeResolver(resume, findOutAction());
        ResumeProposal streamed = resolver.streamingProposal();
        addWarnings(streamed.warnings());
        proposal = new ResumeProposal(streamed.point(), streamed.source(), List.of());
        state.setResumeProposal(proposal);
        listingPoint = streamed.point().kind() == ResumePoint.Kind.AFTER ? streamed.point() : null;
        checkEachFile = streamed.point().kind() == ResumePoint.Kind.NOT_AT_DESTINATION;
        resumeAtTheListing = true;
        return streamed;
    }

    /** Adds warnings to the published ones (from several threads while streaming). */
    private void addWarnings(List<String> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        synchronized (warningsLock) {
            List<String> all = new ArrayList<>(state.getWarnings());
            all.addAll(warnings);
            state.setWarnings(all);
        }
        notifyWatcher();
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
        preparing = true;
        synchronized (phaseLock) {
            preparationStoppable = true;
        }
        markRunning();
        state.setListingInProgress(true);
        startPhase();
        try {
            checkCancelled();
            resolveStepsIfNeeded();
            phaseEnd = barrierIndex;
            finalPhase = false;
            dryRunSteps = processSteps();
            resolver = new ResumeResolver(resume, findOutAction());
            listingPoint = resolver.listingPoint().orElse(null);
            analysisOnDemand = listingPoint == null && resolver.probesTheDestination();
            runListings();
            commitPhase();
            if (listingFailed.get()) {
                state.setStatus(PipelineStatus.ERROR);
                return;
            }
            orderedItems = ResumeResolver.order(state.getWorkItems());
            proposal = resolver.propose(orderedItems, this::analyseNow);
            state.setResumeProposal(proposal);
            resolver.apply(proposal.point(), proposal.source(), orderedItems);
            analyseTheSelectedItems();
            projectItems();
            synchronized (phaseLock) {
                checkCancelled(); // a cancelPreparation() until now stops it; later, it has no effect
                preparationStoppable = false;
            }
            state.setStatus(PipelineStatus.PREPARED);
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, true);
        } finally {
            dryRunSteps = null;
            listingPoint = null;
            analysisOnDemand = false;
            state.setListingInProgress(false);
            synchronized (phaseLock) {
                preparationStoppable = false;
            }
            endPhase();
            preparing = false;
        }
    }

    /**
     * The items whose analysis was deferred at the listing that {@link #analyseDeferred()} analyses: the ones to copy
     * (a manual resume point selected them again) and the skipped ones the user asked for
     * ({@link #requestAnalysis}); empty when the preparation deferred nothing.
     */
    List<WorkItemExecution> toAnalyse() {
        return orderedItems.stream()
                .filter(item -> item.isAnalysisDeferred() && (item.getStatus() == ItemStatus.PENDING
                        || item.getStatus() == ItemStatus.SKIPPED && requestedAnalysis.contains(item)))
                .toList();
    }

    /**
     * Asks {@link #analyseDeferred()} to also analyse these skipped items, so that their planned processing is known:
     * they stay skipped, for the same reason. The items already analysed (or in error) are left out.
     */
    void requestAnalysis(Collection<WorkItemExecution> items) {
        for (WorkItemExecution item : items) {
            if (item.isAnalysisDeferred() && item.getStatus() == ItemStatus.SKIPPED) {
                requestedAnalysis.add(item);
            }
        }
    }

    /**
     * Analyses the items a manual resume point selected again although their analysis was deferred at the listing,
     * and the skipped ones asked for ({@link #requestAnalysis}: they stay skipped)
     * ({@link #toAnalyse()}), like the preparation: the steps before the barrier, then the dry run of the process
     * steps (spec deferred-analysis §1). They stop at the barrier PENDING (the analysis never escapes the resume
     * point), an item failing its analysis ends ERROR. Status RUNNING meanwhile, PREPARED again afterwards, also
     * when the analysis is cancelled ({@link #cancelAnalysis()}): the items not analysed then stay deferred and
     * {@link #execute} analyses them first, as without this analysis.
     * <p>
     * Nothing to analyse, or a {@link #cancel()} of the plan already pending: returns at once, without any phase
     * (no status change, no notification); a pending cancel is kept for the execution.
     *
     * @throws IllegalStateException the plan is not PREPARED
     */
    void analyseDeferred() {
        List<WorkItemExecution> items;
        synchronized (phaseLock) {
            if (state.getStatus() != PipelineStatus.PREPARED) {
                throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", state.getStatus()));
            }
            items = toAnalyse();
            if (cancelRequested || items.isEmpty()) {
                return;
            }
            analysing = true;
        }
        // a skipped item asked for stays skipped: the barrier would make it PENDING (to copy)
        Map<WorkItemExecution, String> skipped = new HashMap<>();
        Set<WorkItemExecution> skippedByPoint = new HashSet<>();
        for (WorkItemExecution exec : items) {
            if (exec.getStatus() == ItemStatus.SKIPPED) {
                skipped.put(exec, exec.getSkipReason());
                if (exec.isSkippedByResumePoint()) {
                    skippedByPoint.add(exec);
                }
            }
        }
        preparing = true;
        markRunning();
        startPhase();
        try {
            checkCancelled();
            phaseEnd = barrierIndex;
            finalPhase = false;
            dryRunSteps = processSteps();
            for (WorkItemExecution exec : items) {
                submitItem(exec, 0, barrierIndex);
            }
            awaitCompletion();
            projectItems();
        } catch (InterruptedException e) {
            onAnalysisInterrupted();
        } finally {
            dryRunSteps = null;
            shutdownTasks(); // every task is over before the cancel request is forgotten (runItem reads it)
            skipped.forEach((exec, reason) -> {
                if (exec.getStatus() != ItemStatus.ERROR) {
                    if (skippedByPoint.contains(exec)) {
                        exec.setSkippedByResumePoint(reason);
                    } else {
                        exec.setSkipped(reason); // ignored by the user: still ignored
                    }
                }
            });
            requestedAnalysis.removeAll(items);
            synchronized (phaseLock) {
                analysing = false;
                cancelRequested = false; // requested during this analysis (checked above): it only stopped it
                phaseThread = null; // a later cancel() cancels the plan, not this phase
                state.setStatus(PipelineStatus.PREPARED); // before the terminal notification of endPhase
            }
            endPhase();
            preparing = false;
        }
    }

    /** Like {@link #onPhaseInterrupted()}, the plan staying PREPARED. */
    private void onAnalysisInterrupted() {
        boolean byCancel;
        synchronized (phaseLock) {
            byCancel = cancelRequested;
            phaseThread = null;
        }
        if (byCancel) {
            Thread.interrupted();
        }
        shutdownTasks();
        if (!byCancel) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops {@link #prepare()} ({@link #cancel()}): it ends CANCELLED. No effect once the plan is prepared or when no
     * preparation runs: a late request must not cancel the execution to come. Callable from any thread.
     */
    void cancelPreparation() {
        synchronized (phaseLock) {
            if (preparationStoppable) {
                cancel();
            }
        }
    }

    /**
     * Stops {@link #analyseDeferred()}, the plan staying PREPARED; no effect when no analysis runs (a late request
     * must not cancel the plan). Callable from any thread.
     */
    void cancelAnalysis() {
        synchronized (phaseLock) {
            if (analysing) {
                cancel();
            }
        }
    }

    /** True while {@link #prepare()} or {@link #analyseDeferred()} runs: an item without projection is then not analysed yet. */
    boolean isPreparing() {
        return preparing;
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
     * The destination probe checks this item: analysed now (on the phase thread) when it is not yet.
     *
     * @return false when its analysis failed: it cannot be probed
     */
    private boolean analyseNow(WorkItemExecution exec) throws InterruptedException {
        if (exec.isAnalysisDeferred()) {
            runItem(exec, 0, barrierIndex);
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted");
            }
            checkCancelled();
        }
        return exec.getStatus() != ItemStatus.ERROR;
    }

    /**
     * With the analysis on demand, once the resume point is applied: the items it selects are analysed, the
     * others stay unanalysed, their preparation over (like the items skipped at the listing).
     */
    private void analyseTheSelectedItems() throws InterruptedException {
        for (WorkItemExecution exec : orderedItems) {
            if (exec.isAnalysisDeferred() && exec.getStatus() == ItemStatus.SKIPPED) {
                exec.deferAnalysis(exec.getSkipReason()); // prepared, still not analysed
            }
        }
        for (WorkItemExecution exec : orderedItems) {
            if (exec.isAnalysisDeferred() && exec.getStatus() == ItemStatus.PENDING) {
                submitItem(exec, 0, barrierIndex);
            }
        }
        awaitCompletion();
        checkCancelled();
    }

    /**
     * The dry run of the process steps for every item prepared without error, selected or not, so that a manual
     * resume point chosen later needs nothing more (spec pattern-helper §4.3). Most items were projected as soon
     * as they were analysed ({@link #projectEarly}): this completes the others (e.g. filtered before the barrier).
     * A failing dry run is shown, never an item error. The dry run uses the same action instances as the
     * execution, possibly from several threads: dryRun must not change their state.
     */
    private void projectItems() throws InterruptedException {
        List<PipelineStep<IProcessAction>> steps = processSteps();
        for (WorkItemExecution exec : orderedItems) {
            checkCancelled();
            if (exec.getStatus() != ItemStatus.ERROR && !exec.isAnalysisDeferred() && exec.getProjection() == null) {
                exec.setProjection(DryRunner.project(exec.getWorkItem(), steps));
                checkTarget(exec, registry.ticket());
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
     * Leaves these items out of the run (ignore), or brings them back: an item brought back is decided again by
     * the resume point in force (override, or the proposed one). Only the items to copy or skipped by the resume
     * point can be ignored, not the failed ones.
     *
     * @throws IllegalStateException the plan is not PREPARED (being prepared, executed or already executed)
     */
    void setIgnored(Collection<WorkItemExecution> items, boolean ignore, ResumePoint override) {
        if (state.getStatus() != PipelineStatus.PREPARED) {
            throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", state.getStatus()));
        }
        for (WorkItemExecution item : items) {
            if (ignore && !item.isIgnored()
                    && (item.getStatus() == ItemStatus.PENDING || item.getStatus() == ItemStatus.SKIPPED)) {
                item.setIgnored(ResourcesEngine.getString("plan.skip.ignored"));
            } else if (!ignore && item.isIgnored()) {
                item.clearIgnored();
            }
        }
        if (!ignore) {
            if (override != null) {
                resolver.apply(override, ResumeSource.MANUAL, orderedItems);
            } else {
                resolver.apply(proposal.point(), proposal.source(), orderedItems);
            }
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
                    // an item skipped at the listing, selected again by a manual point, is analysed first
                    submitItem(exec, exec.isAnalysisDeferred() ? 0 : barrierIndex, itemSteps.size());
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

    /** The pipeline configuration, null for an executor built from pre-resolved steps. */
    PipelineConfig pipelineConfig() {
        return pipelineConfig;
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
        deferAtTheListing(exec);
        state.getWorkItems().add(exec);
        if (resumeAtTheListing) {
            listedItems.add(exec);
        }
        notifyWatcher();
        if (exec.isAnalysisDeferred() || exec.getStatus() == ItemStatus.ERROR) {
            return;
        }
        try {
            if (checkEachFile) {
                submitTask(() -> streamItem(exec));
            } else {
                submitItem(exec, 0, phaseEnd);
            }
        } catch (RejectedExecutionException e) {
            if (taskExecutor.isShutdown()) {
                // the phase shut its executor down (cancel, interrupt) while this listing was emitting
                throw new PhaseStopped();
            }
            throw e;
        }
    }

    /**
     * Skips an item the resume point known before the listing does not select, without analysing it (its key is
     * frozen now, the resolver applies the same point to it at the end of the preparation); with the analysis on
     * demand, leaves every item unanalysed for now.
     */
    private void deferAtTheListing(WorkItemExecution exec) {
        if (resumeAtTheListing) {
            // streaming: no ordering later, the key is frozen before any step may replace the work item
            exec.setResumeKey(ItemKey.of(exec.getWorkItem()).orElse(null));
            if (exec.getResumeKey().isEmpty()) { // like ResumeResolver.apply: no place in the resume order
                exec.setError(CopybotException.ofResource("resume.item.no-date", exec.getWorkItem().getNameDisplay()));
                return;
            }
        }
        ResumePoint point = listingPoint;
        if (point == null) {
            if (analysisOnDemand) {
                exec.setResumeKey(ItemKey.of(exec.getWorkItem()).orElse(null));
                exec.markAnalysisDeferred();
            }
            return;
        }
        exec.setResumeKey(ItemKey.of(exec.getWorkItem()).orElse(null));
        if (exec.getResumeKey().filter(key -> !point.selects(key)).isPresent()) {
            exec.deferAnalysis(ResumeResolver.skipReason(point, ResumeSource.STATE));
        }
    }

    /**
     * Streaming with the point NOT_AT_DESTINATION: the analyses, then the check of the file's own target, then the
     * rest of the steps unless it is at the destination (skipped by the resume point).
     */
    private void streamItem(WorkItemExecution exec) {
        runItem(exec, 0, barrierIndex);
        if (exec.getStatus() != ItemStatus.PENDING || Thread.currentThread().isInterrupted() || cancelRequested) {
            return; // failed, filtered, or stopped
        }
        Optional<String> found;
        try {
            found = resolver.checkDestination(exec);
        } catch (RuntimeException e) {
            exec.setError(e);
            notifyWatcher();
            return;
        }
        addWarnings(resolver.drainWarnings());
        if (found.isPresent()) {
            exec.setSkippedByResumePoint(found.get());
            notifyWatcher();
            return;
        }
        runItem(exec, barrierIndex, itemSteps.size());
    }

    private void submitItem(WorkItemExecution exec, int fromStep, int toStep) {
        long ticket = registry.ticket(); // its place among the resource waiters: the order of submission
        submitTask(() -> runItem(exec, fromStep, toStep, ticket));
    }

    /**
     * Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING, an item the
     * out step skipped ends SKIPPED with its reason.
     */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep) {
        runItem(exec, fromStep, toStep, registry.ticket());
    }

    /** @param ticket the item's place among the resource waiters ({@link ResourceRegistry#ticket()}) */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep, long ticket) {
        try {
            if (!processWhileListing) {
                listingGate.await();
            }
            StepOutcome outcome = StepOutcome.CONTINUE;
            for (int i = fromStep; i < toStep; i++) {
                PipelineStep<?> step = itemSteps.get(i);
                Set<String> footprint = FootprintResolver.resolve(step.getAction(), exec.getWorkItem(), step.getConfig(), i);
                exec.setWaitingResources(i, footprint);
                notifyWatcher();
                registry.acquireAll(footprint, ticket);
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
                    projectEarly(exec, ticket);
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
     * While preparing, the target of an item is known as soon as it is analysed (spec pattern-helper §4.3), and
     * checked at once (spec conflict-check §2).
     */
    private void projectEarly(WorkItemExecution exec, long ticket) throws InterruptedException {
        List<PipelineStep<IProcessAction>> steps = dryRunSteps;
        if (steps != null) {
            exec.setProjection(DryRunner.project(exec.getWorkItem(), steps));
            checkTarget(exec, ticket); // its own place in the queue: right after its analysis, not after all of them
        }
    }

    /**
     * What the out step would find at the targets of a file to copy (spec conflict-check §4): each produced item is
     * checked, the most severe result kept. Under the disks of the out step's targets (not the source disk), with
     * the file's ticket so that it follows its analysis. A plugin failure is UNKNOWN, never an item error. Nothing
     * for conflictCheck "none", a file not selected, or a dry run that projected nothing.
     */
    private void checkTarget(WorkItemExecution exec, long ticket) throws InterruptedException {
        ConflictCheck level = conflictCheck;
        IOutAction out = findOutAction();
        if (level == ConflictCheck.NONE || out == null || exec.getStatus() != ItemStatus.PENDING
                || !(exec.getProjection() instanceof Projection.Projected projected)) {
            return;
        }
        // the destination only: the check reads nothing on the source, it must not hold the card the analyses read
        Set<String> footprint = FootprintResolver.forTargets(out, exec.getWorkItem());
        registry.acquireAll(footprint, ticket);
        try {
            if (cancelRequested) {
                throw new InterruptedException("cancelled");
            }
            TargetCheck result = null;
            for (WorkItem produced : projected.items()) {
                result = TargetCheck.mostSevere(result, check(out, produced, level == ConflictCheck.FULL));
            }
            exec.setTargetCheck(result == null ? TargetCheck.UNKNOWN : result);
        } finally {
            registry.releaseAll(footprint);
        }
    }

    private static TargetCheck check(IOutAction out, WorkItem produced, boolean compareContent) {
        try {
            TargetCheck check = out.checkTarget(produced, compareContent);
            return check == null ? TargetCheck.UNKNOWN : check;
        } catch (RuntimeException e) {
            LOG.debug(e, "plan.check.failed", produced.getNameDisplay(), String.valueOf(e));
            return TargetCheck.UNKNOWN;
        }
    }

    /** How far the plan checks the existing targets; from the pipeline, quick without one. */
    void setConflictCheck(ConflictCheck conflictCheck) {
        this.conflictCheck = conflictCheck;
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
