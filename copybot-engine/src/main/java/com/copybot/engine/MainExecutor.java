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
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs one pipeline: one virtual thread per listing and per work item,
 * concurrency bounded by the ResourceRegistry (see the design spec).
 *
 * <p>This class holds the phases (single-phase run, prepare, analysis of deferred items, execute) and their control
 * (cancel, pause); the tasks of a phase are a {@link TaskGroup}, the steps of an item run in an {@link ItemRunner},
 * the watcher is notified through a {@link CoalescingNotifier}.
 */
public class MainExecutor implements Runnable {

    private static final System.Logger LOGGER = System.getLogger(MainExecutor.class.getName());

    /** Minimum delay between two watcher notifications (coalescing, see {@link CoalescingNotifier#changed()}). */
    static final long WATCHER_PERIOD_MILLIS = CoalescingNotifier.PERIOD_MILLIS;

    private final PipelineConfig pipelineConfig;
    private final ResourceRegistry registry;
    private final PipelineState state;

    /**
     * Unique per pipeline run (prepare and execute share it), handed to the out step: it names its temporary
     * files and recognises the ones a crashed run left behind (spec safe-write §3).
     */
    /** The directories the out step created, for the post-processing (spec post-process). */
    private final Set<Path> createdDirectories = ConcurrentHashMap.newKeySet();

    private final WriteContext writeContext = WriteContext.newRun(createdDirectories::add);

    /** Launched at the end of a copy phase. */
    private PostProcess postProcess;

    private List<PipelineStep<IInAction>> inSteps;
    /** volatile: {@link Plan#projectionOf} reads it from another thread while the plan is being prepared */
    private volatile List<PipelineStep<?>> itemSteps;

    /**
     * null: single-phase run without barrier nor resume (historical behaviour of run(), and {@link #stream()} without
     * resume).
     */
    private final ResumeContext resume;

    /** Number of item steps before the preparation barrier (the analyse steps). */
    private int barrierIndex;

    /** Exclusive end of the steps run by the items the listings emit: the barrier while preparing, all steps otherwise. */
    private volatile int phaseEnd;

    /** Set once the configuration warnings are published: prepare() collects them, execute() keeps them. */
    private boolean warningsCollected;

    /** From the start of {@link #prepare()} until it returns, and during {@link #analyseDeferred()}. */
    private volatile boolean preparing;
    /** The skipped items to analyse too ({@link #requestAnalysis}). */
    private final Set<WorkItemExecution> requestedAnalysis = ConcurrentHashMap.newKeySet();
    /** {@link #analyseDeferred()} runs its phase. Guarded by phaseLock. */
    private boolean analysing;
    /** {@link #prepare()} runs and has not published PREPARED yet: {@link #cancelPreparation()} stops it. Guarded by phaseLock. */
    private boolean preparationStoppable;

    /**
     * While preparing, the resume point known before the listing (see {@link ResumeResolver#listingPoint}): the
     * items it does not select are skipped as soon as they are listed, without analysis. null otherwise.
     */
    private volatile ResumePoint listingPoint;
    /** Where {@link #listingPoint} comes from: the state cursor, or a point chosen by the user (the skip reason). */
    private volatile ResumeSource listingSource = ResumeSource.STATE;

    /**
     * A resume point chosen by the user before the preparation or the streaming run (spec manual-point §1): it is
     * the listing point and the proposal, like a cursor at that point; null for the automatic one.
     */
    private volatile ResumePoint chosenPoint;
    /**
     * The last phase was a preparation ({@link #prepare()}): a CANCELLED status is then a stopped preparation, whose
     * rows can be used when its listing is complete. Guarded by phaseLock.
     */
    private boolean lastPhaseWasPreparation;
    /** A listing of the current preparation was stopped (cancel, interrupt) before its end. */
    private final AtomicBoolean listingStopped = new AtomicBoolean(false);

    /** {@link #stream()}: processing while listing, the resume applied at the listing. */
    private volatile boolean streaming;
    /** While streaming with resume: the keys are frozen at the listing, a file without date is an error there. */
    private volatile boolean resumeAtTheListing;
    /** While streaming with the point NOT_AT_DESTINATION: each file is checked once analysed. */
    private volatile boolean checkEachFile;
    /** While streaming with resume: the listed files (not the forks), for the cursor. */
    private final Queue<WorkItemExecution> listedItems = new ConcurrentLinkedQueue<>();
    private final Object warningsLock = new Object();
    /** The actions whose {@link IAction#beforeAll} ran in the current phase, in that order. Phase thread only. */
    private final List<IAction> begunActions = new ArrayList<>();

    /**
     * While preparing, true when the resume point comes from the destination probe: the items are not analysed at
     * the listing, the probe analyses the ones it checks, then the point the ones it selects.
     */
    private volatile boolean analysisOnDemand;

    /** Where the own out patterns are kept (spec own-out-pattern §2); null: in memory only. */
    private volatile ResumeStateStore outPatternStore;
    /** The own out patterns by item, read at the start of a listing phase; given to the listed items that match. */
    private final Map<ItemKey, String> ownPatterns = new ConcurrentHashMap<>();

    private ResumeResolver resolver;
    private ResumeProposal proposal;
    private List<WorkItemExecution> orderedItems = List.of();

    private final TaskGroup tasks = new TaskGroup();
    private final CoalescingNotifier<PipelineState> notifier;
    private final ItemRunner itemRunner;

    private CountDownLatch listingGate;
    private final AtomicBoolean listingFailed = new AtomicBoolean(false);

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
     *                {@link #prepare()}, the caller's thread. Exceptions it throws are ignored (the first one
     *                logged). The {@link PipelineState} handed over is the live, mutating state: read it, do not
     *                retain it. The watcher must not call close() nor any other blocking engine operation
     *                (the phase notifying it would wait for itself).
     */
    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry,
                        ResumeContext resume) {
        this.pipelineConfig = pipelineConfig;
        this.registry = registry;
        this.resume = resume;
        this.state = new PipelineState();
        this.state.setRegistry(registry);
        this.notifier = new CoalescingNotifier<>(watcher, state);
        this.itemRunner = newItemRunner();
        this.itemRunner.setConflictCheck(pipelineConfig.conflictCheckMode());
        this.postProcess = PostProcess.of(pipelineConfig.postProcess());
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
        this.registry = registry;
        this.resume = resume;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.barrierIndex = barrierIndex;
        this.state = new PipelineState();
        this.state.setRegistry(registry);
        this.notifier = new CoalescingNotifier<>(watcher, state);
        this.itemRunner = newItemRunner();
        this.itemRunner.setProcessWhileListing(processWhileListing);
        this.postProcess = PostProcess.of(null);
    }

    // visible for tests
    void setPostProcess(PostProcess postProcess) {
        this.postProcess = postProcess;
    }

    private ItemRunner newItemRunner() {
        return new ItemRunner(registry, state, tasks, notifier, writeContext, () -> itemSteps, this::findOutAction,
                () -> cancelRequested);
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
        itemRunner.setProcessWhileListing(true);
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
        return state.getStatus().isTerminal();
    }

    /**
     * Cancellation (cancel() or an interrupt of the phase thread): unblock every task still parked in
     * acquireAll/IO and let it release its permits, THEN restore an external interrupt (awaitTermination
     * would return immediately with the flag set, leaving writers to be killed mid-stream by JVM exit).
     * The interrupt sent by cancel() is not restored: it must not leak to the caller (e.g. a UI worker).
     */
    private void onPhaseInterrupted() {
        stopAfterInterrupt(true);
    }

    /**
     * See {@link #onPhaseInterrupted()}.
     *
     * @param cancelled the pipeline ends CANCELLED; false for the analysis of deferred items, the plan staying PREPARED
     */
    private void stopAfterInterrupt(boolean cancelled) {
        boolean byCancel;
        synchronized (phaseLock) { // cancel() interrupts under this lock: its interrupt is already delivered
            byCancel = cancelRequested;
            // a later cancel() must not interrupt again: it would cut the grace period of tasks.shutdown()
            // short, or wipe (in endPhase) the external interrupt restored below
            phaseThread = null;
        }
        if (byCancel) {
            Thread.interrupted();
        }
        if (cancelled) {
            state.setStatus(PipelineStatus.CANCELLED);
        }
        tasks.shutdown();
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
        synchronized (phaseLock) {
            lastPhaseWasPreparation = false;
        }
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
            loadOwnPatterns();
            ResumeProposal streamed = streaming && resume != null ? resumeAtTheListing() : null;
            beginActions(inSteps); // nothing written yet: a failure of beforeAll is a preparation failure
            beginActions(itemSteps);
            preparing = false;
            phaseEnd = itemSteps.size();
            itemRunner.setFinalPhase(true);
            runListings();
            endActions();
            commitPhase();
            // like execute(): the final status is published once, after the cursor save
            PipelineStatus finalStatus = resolveFinalStatus();
            if (streamed != null) {
                orderedItems = ResumeResolver.order(listedItems); // the keys are frozen since the listing
                if (!listingFailed.get() && !saveCursor(streamed.point(), streamed.source())) {
                    finalStatus = PipelineStatus.ERROR;
                }
            }
            runPostProcess();
            state.setStatus(finalStatus);
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, preparing);
        } finally {
            listingPoint = null;
            state.setListingInProgress(false);
            endActionsQuietly(true);
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
        ResumePoint chosen = chosenPoint;
        ResumeProposal streamed;
        if (chosen != null) {
            resolver.readCursor(); // the cursor never moves back (nextCursor)
            streamed = new ResumeProposal(chosen, ResumeSource.MANUAL, List.of());
            listingSource = ResumeSource.MANUAL;
        } else {
            streamed = resolver.streamingProposal();
            listingSource = ResumeSource.STATE;
        }
        addWarnings(streamed.warnings());
        proposal = new ResumeProposal(streamed.point(), streamed.source(), List.of());
        state.setResumeProposal(proposal);
        ResumePoint.Kind kind = streamed.point().kind();
        listingPoint = kind == ResumePoint.Kind.AFTER || kind == ResumePoint.Kind.FROM ? streamed.point() : null;
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
        notifier.changed();
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
            lastPhaseWasPreparation = true;
        }
        listingStopped.set(false);
        state.setListingComplete(false);
        markRunning();
        state.setListingInProgress(true);
        startPhase();
        try {
            checkCancelled();
            resolveStepsIfNeeded();
            loadOwnPatterns();
            phaseEnd = barrierIndex;
            itemRunner.setFinalPhase(false);
            itemRunner.setDryRunSteps(processSteps());
            resolver = new ResumeResolver(resume, findOutAction());
            ResumePoint chosen = chosenPoint;
            if (chosen != null) {
                // like a cursor at that point (spec manual-point §1): no probe, the files before it not analysed
                listingPoint = chosen;
                listingSource = ResumeSource.MANUAL;
                analysisOnDemand = false;
                resolver.readCursor(); // never moved back by nextCursor
            } else {
                listingPoint = resolver.listingPoint().orElse(null);
                listingSource = ResumeSource.STATE;
                analysisOnDemand = listingPoint == null && resolver.probesTheDestination();
            }
            beginActions(inSteps);
            beginActions(itemSteps.subList(0, barrierIndex));
            runListings();
            commitPhase();
            if (listingFailed.get()) {
                state.setStatus(PipelineStatus.ERROR);
                return;
            }
            orderedItems = ResumeResolver.order(state.getWorkItems());
            proposal = chosen != null
                    ? new ResumeProposal(chosen, ResumeSource.MANUAL, List.of())
                    : resolver.propose(orderedItems, new ResumeResolver.Analyser() {
                        @Override
                        public boolean analyse(WorkItemExecution item) throws InterruptedException {
                            return analyseNow(item);
                        }

                        @Override
                        public void analyseAll(List<WorkItemExecution> items) throws InterruptedException {
                            analyseInParallel(items);
                        }
                    });
            state.setResumeProposal(proposal);
            resolver.apply(proposal.point(), proposal.source(), orderedItems);
            analyseTheSelectedItems();
            projectItems();
            endActions();
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
            itemRunner.setDryRunSteps(null);
            listingPoint = null;
            analysisOnDemand = false;
            state.setListingInProgress(false);
            synchronized (phaseLock) {
                preparationStoppable = false;
            }
            endActionsQuietly(true);
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
        if (isStoppedAfterTheListing()) {
            // a stopped preparation: only the files asked for, never every file left unanalysed by the stop
            return state.getWorkItems().stream()
                    .filter(item -> item.isAnalysisDeferred() && requestedAnalysis.contains(item))
                    .toList();
        }
        return orderedItems.stream()
                .filter(item -> item.isAnalysisDeferred() && (item.getStatus() == ItemStatus.PENDING
                        || item.getStatus() == ItemStatus.SKIPPED && requestedAnalysis.contains(item)))
                .toList();
    }

    /**
     * Asks {@link #analyseDeferred()} to also analyse these skipped items, so that their planned processing is known:
     * they stay skipped, for the same reason. The items already analysed (or in error) are left out. On a preparation
     * stopped after its listing, the files the stop left unanalysed can be asked for too (spec manual-point §2).
     */
    void requestAnalysis(Collection<WorkItemExecution> items) {
        boolean stopped = isStoppedAfterTheListing();
        for (WorkItemExecution item : items) {
            if (item.isAnalysisDeferred() && item.getStatus() == ItemStatus.SKIPPED) {
                requestedAnalysis.add(item);
            } else if (stopped && item.getStatus() == ItemStatus.PENDING && (!item.isPrepared() || item.isAnalysisDeferred())) {
                item.markAnalysisDeferred();
                requestedAnalysis.add(item);
            }
        }
    }

    /** @param store where the own out patterns are read and kept, null: in memory only (spec own-out-pattern §2) */
    void setOutPatternStore(ResumeStateStore store) {
        outPatternStore = store;
    }

    ResumeStateStore outPatternStore() {
        return outPatternStore;
    }

    /**
     * The own out patterns of the state file, read again at the start of a listing phase; without a store, the ones
     * in memory are kept.
     *
     * @throws CopybotException resume.state.invalid, a preparation failure like an unreadable cursor
     */
    private void loadOwnPatterns() {
        ResumeStateStore store = outPatternStore;
        if (store == null) {
            return;
        }
        Map<ItemKey, String> read = store.readOutPatterns();
        ownPatterns.clear();
        ownPatterns.putAll(read);
    }

    /** A listed item whose key has an own out pattern gets it. */
    private void applyOwnPattern(WorkItem workItem) {
        if (!ownPatterns.isEmpty()) {
            ItemKey.of(workItem).map(ownPatterns::get).ifPresent(workItem::setOutPattern);
        }
    }

    /**
     * Before {@link #prepare()} or {@link #stream()}: a resume point chosen by the user, which acts like a cursor at
     * that point (spec manual-point §1); null for the automatic one.
     */
    void chooseResumePoint(ResumePoint point) {
        chosenPoint = point;
    }

    /**
     * A preparation stopped after its listing was complete (spec manual-point §2): a resume point can be chosen
     * from its rows and some of them analysed ({@link #analyseDeferred()}); Prepare starts again from the point.
     */
    boolean isStoppedAfterTheListing() {
        synchronized (phaseLock) {
            return stoppedAfterTheListing();
        }
    }

    /** Caller holds phaseLock. */
    private boolean stoppedAfterTheListing() {
        return state.getStatus() == PipelineStatus.CANCELLED && lastPhaseWasPreparation && state.isListingComplete()
                && phaseThread == null && !preparing;
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
        PipelineStatus endStatus;
        synchronized (phaseLock) {
            boolean stopped = stoppedAfterTheListing();
            if (state.getStatus() != PipelineStatus.PREPARED && !stopped) {
                throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", state.getStatus()));
            }
            items = toAnalyse();
            if (items.isEmpty() || !stopped && cancelRequested) {
                return;
            }
            if (stopped) {
                // a stopped preparation (spec manual-point §2): the stop that ended it is not a stop of this analysis,
                // and the status leaves CANCELLED under the lock, so that a stop of the analysis is never lost
                cancelRequested = false;
                markRunning();
            }
            endStatus = stopped ? PipelineStatus.CANCELLED : PipelineStatus.PREPARED;
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
            itemRunner.setFinalPhase(false);
            itemRunner.setDryRunSteps(processSteps());
            if (beginAnalysis(items)) {
                for (WorkItemExecution exec : items) {
                    itemRunner.submit(exec, 0, barrierIndex);
                }
                tasks.awaitCompletion();
                projectItems();
            }
        } catch (InterruptedException e) {
            stopAfterInterrupt(false); // the plan stays PREPARED
        } finally {
            itemRunner.setDryRunSteps(null);
            tasks.shutdown(); // every task is over before the cancel request is forgotten (the item runner reads it)
            endActionsQuietly(false); // the plan stays as it was: a failure of afterAll is only logged
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
                state.setStatus(endStatus); // PREPARED, or CANCELLED for a stopped preparation; before the terminal notification of endPhase
            }
            endPhase();
            preparing = false;
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
            itemRunner.run(exec, 0, barrierIndex);
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted");
            }
            checkCancelled();
        }
        return exec.getStatus() != ItemStatus.ERROR;
    }

    /** With the analysis on demand: a batch of items the destination probe checks, analysed at once. */
    private void analyseInParallel(List<WorkItemExecution> items) throws InterruptedException {
        for (WorkItemExecution exec : items) {
            if (exec.isAnalysisDeferred()) {
                itemRunner.submit(exec, 0, barrierIndex);
            }
        }
        tasks.awaitCompletion();
        checkCancelled();
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
                itemRunner.submit(exec, 0, barrierIndex);
            }
        }
        tasks.awaitCompletion();
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
                itemRunner.checkTarget(exec, registry.ticket());
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

    /** The pattern the out step applies to a file without own pattern; empty: no own pattern supported. */
    Optional<String> defaultOutPattern() {
        IOutAction out = findOutAction();
        return out == null ? Optional.empty() : out.defaultOutPattern();
    }

    /**
     * Gives these items their own out pattern, on the analysed ones only (they have a projection), or removes it (null,
     * blank or the out step's default) from all of them: on the work item and on the items its dry run projected, in
     * memory and in the state file, then the target of the analysed ones is checked again (spec own-out-pattern §2).
     * Blocking.
     *
     * @throws IllegalStateException the plan is neither PREPARED nor stopped after its listing, or the out step
     *                               supports no own pattern
     * @throws CopybotException      pattern.syntax or resume.state.write-error: nothing changed
     */
    void setOutPattern(Collection<WorkItemExecution> items, String pattern) {
        synchronized (phaseLock) {
            if (state.getStatus() != PipelineStatus.PREPARED && !stoppedAfterTheListing()) {
                throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", state.getStatus()));
            }
        }
        String fallback = defaultOutPattern().orElseThrow(
                () -> new IllegalStateException(ResourcesEngine.getString("plan.out-pattern.unsupported")));
        String own = pattern == null || pattern.isBlank() || pattern.trim().equals(fallback.trim()) ? null : pattern.trim();
        if (own != null) {
            OutPattern.parse(own); // refused now, never at the write
        }
        List<WorkItemExecution> analysed = items.stream().filter(item -> item.getProjection() != null).toList();
        // a removal reaches the unanalysed items too: restored at the listing, they could never lose it otherwise
        List<WorkItemExecution> changed = own == null ? List.copyOf(items) : analysed;
        Map<ItemKey, String> patterns = new HashMap<>(ownPatterns);
        for (WorkItemExecution item : changed) {
            item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem())).ifPresent(key -> {
                if (own == null) {
                    patterns.remove(key);
                } else {
                    patterns.put(key, own);
                }
            });
        }
        ResumeStateStore store = outPatternStore;
        if (store != null && !patterns.equals(ownPatterns)) {
            store.writeOutPatterns(patterns); // first: a failing write changes nothing
        }
        ownPatterns.clear();
        ownPatterns.putAll(patterns);
        for (WorkItemExecution item : changed) {
            item.getWorkItem().setOutPattern(own);
            if (item.getProjection() instanceof Projection.Projected projected) {
                projected.items().forEach(produced -> produced.setOutPattern(own));
            }
        }
        for (WorkItemExecution item : analysed) {
            item.setTargetCheck(null); // a file not selected is not checked: no result about its former target
            try {
                itemRunner.checkTarget(item, registry.ticket()); // nothing for conflictCheck "none"
            } catch (InterruptedException e) {
                if (!cancelRequested) { // a pending cancel of the plan is not an interrupt of the caller
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
        notifier.changed();
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
        synchronized (phaseLock) {
            lastPhaseWasPreparation = false; // a stopped execution is not a stopped preparation
        }
        markRunning();
        startPhase();
        try {
            checkCancelled();
            itemRunner.setFinalPhase(true);
            boolean analysesToo = orderedItems.stream()
                    .anyMatch(exec -> exec.getStatus() == ItemStatus.PENDING && exec.isAnalysisDeferred());
            beginActions(itemSteps.subList(analysesToo ? 0 : barrierIndex, itemSteps.size()));
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    // an item skipped at the listing, selected again by a manual point, is analysed first
                    itemRunner.submit(exec, exec.isAnalysisDeferred() ? 0 : barrierIndex, itemSteps.size());
                }
            }
            tasks.awaitCompletion();
            endActions();
            commitPhase(); // a cancelled run never writes the cursor, a later cancel never interrupts its write
            // The final status is published once, after the cursor save: a cursor write failure
            // must never be seen by watchers as SUCCESS followed by ERROR.
            PipelineStatus finalStatus = resolveFinalStatus();
            if (!saveCursor(point, source)) {
                finalStatus = PipelineStatus.ERROR;
            }
            runPostProcess();
            state.setStatus(finalStatus);
        } catch (InterruptedException e) {
            onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            onPhaseFailed(e, false);
        } finally {
            endActionsQuietly(true);
            endPhase();
        }
    }

    /**
     * {@link IAction#beforeAll} of the actions of these steps, in order (spec of the lifecycle: {@link IAction}). A
     * failure is thrown, the phase fails: the actions begun before it still get their {@link IAction#afterAll}.
     */
    private void beginActions(List<? extends PipelineStep<?>> steps) {
        for (PipelineStep<?> step : steps) {
            IAction action = step.getAction();
            action.beforeAll();
            begunActions.add(action);
        }
    }

    /**
     * Before the analysis of deferred items: the beforeAll of the analyse steps. A failure fails the items to analyse,
     * as their analysis would, the plan staying as it was.
     *
     * @return false when it failed: nothing is analysed
     */
    private boolean beginAnalysis(List<WorkItemExecution> items) {
        try {
            beginActions(itemSteps.subList(0, barrierIndex));
            return true;
        } catch (RuntimeException | Error e) {
            for (WorkItemExecution exec : items) {
                exec.setError(e);
                exec.markPrepared();
            }
            notifier.changed();
            return false;
        }
    }

    /**
     * {@link IAction#afterAll} of the actions begun in this phase, the last begun first, every one of them called.
     *
     * @throws RuntimeException the first failure, the next ones suppressed in it (or an Error)
     */
    private void endActions() {
        Throwable failure = null;
        for (int i = begunActions.size() - 1; i >= 0; i--) {
            try {
                begunActions.get(i).afterAll();
            } catch (RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        begunActions.clear();
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }

    /**
     * In the finally block of a phase: the {@link IAction#afterAll} a stopped or failed phase did not call, once no
     * item runs any more. Its failure is logged, never thrown.
     *
     * @param recordFailure the failure is also the pipeline's when it has none yet (not for the analysis of deferred
     *                      items: the plan stays as it was)
     */
    private void endActionsQuietly(boolean recordFailure) {
        if (begunActions.isEmpty()) {
            return;
        }
        tasks.shutdown();
        try {
            endActions();
        } catch (RuntimeException | Error e) {
            if (recordFailure) {
                state.recordFailureIfAbsent(e);
            }
            LOGGER.log(System.Logger.Level.WARNING, "afterAll failed at the end of a phase", e);
        }
    }

    /**
     * At the end of a copy phase that was not stopped, before its final status (spec post-process): a failed
     * launch is a warning, the status stays the copy's.
     */
    private void runPostProcess() {
        postProcess.run(createdDirectories).ifPresent(warning -> addWarnings(List.of(warning)));
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
        itemRunner.setListingGate(listingGate);
        for (PipelineStep<IInAction> inStep : inSteps) {
            tasks.submit(() -> runListing(inStep));
        }
        tasks.awaitCompletion();
    }

    private void startPhase() {
        synchronized (phaseLock) {
            phaseThread = Thread.currentThread(); // what cancel() interrupts
        }
        tasks.open();
        notifier.start();
        notifier.changed();
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
        tasks.shutdown();
        notifier.stop(); // includes the final, guaranteed notification
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

    private void runListing(PipelineStep<IInAction> inStep) {
        try {
            Set<String> footprint = FootprintResolver.forListing(inStep.getAction(), inStep.getConfig());
            registry.acquireAll(footprint);
            try {
                inStep.getAction().listFiles(this::emitItem);
            } finally {
                registry.releaseAll(footprint);
            }
            if (Thread.currentThread().isInterrupted() || cancelRequested) {
                listingStopped.set(true); // a plugin that ended its listing early on the stop: maybe incomplete
            }
        } catch (InterruptedException e) {
            listingStopped.set(true);
            Thread.currentThread().interrupt();
        } catch (PhaseStopped e) {
            // the phase is being stopped (see emitItem): not a listing failure. Only this private marker is
            // ignored: any other exception, a plugin's own CancellationException included, fails the run.
            listingStopped.set(true);
        } catch (Throwable t) {
            // A failing listing must not silently look like success: the whole run is marked ERROR.
            state.recordFailureIfAbsent(t); // the first listing failure wins, atomically
            listingFailed.set(true);
        } finally {
            listingGate.countDown();
            if (listingGate.getCount() == 0) {
                state.setListingComplete(!listingStopped.get() && !listingFailed.get());
                state.setListingInProgress(false);
                notifier.changed();
            }
        }
    }

    private void emitItem(WorkItem workItem) {
        // The listing holds its disk for its whole duration, so the registry pause alone would not
        // freeze it: wait here, before creating the item.
        try {
            registry.awaitNotPaused();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelRequested || tasks.isShutdown()) {
                throw stopListing();
            }
            // not an interrupt of ours: the listing stops short, which must fail the run
            throw new IllegalStateException("listing interrupted", e);
        }
        if (cancelRequested) {
            throw stopListing(); // woken by the pause cancel() lifted: emit nothing more
        }
        applyOwnPattern(workItem); // before any analysis, projection, probe or target check
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
        deferAtTheListing(exec);
        state.getWorkItems().add(exec);
        if (resumeAtTheListing) {
            listedItems.add(exec);
        }
        notifier.changed();
        if (exec.isAnalysisDeferred() || exec.getStatus() == ItemStatus.ERROR) {
            return;
        }
        try {
            if (checkEachFile) {
                tasks.submit(() -> streamItem(exec));
            } else {
                itemRunner.submit(exec, 0, phaseEnd);
            }
        } catch (RejectedExecutionException e) {
            if (tasks.isShutdown()) {
                // the phase shut its executor down (cancel, interrupt) while this listing was emitting
                throw stopListing();
            }
            throw e;
        }
    }

    /**
     * The listing calling {@link #emitItem} stops because the phase is being stopped: it is marked incomplete now,
     * even if the plugin swallows the marker returned (to throw).
     */
    private PhaseStopped stopListing() {
        listingStopped.set(true);
        return new PhaseStopped();
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
            exec.deferAnalysis(ResumeResolver.skipReason(point, listingSource));
        }
    }

    /**
     * Streaming with the point NOT_AT_DESTINATION: the analyses, then the check of the file's own target, then the
     * rest of the steps unless it is at the destination (skipped by the resume point).
     */
    private void streamItem(WorkItemExecution exec) {
        itemRunner.run(exec, 0, barrierIndex);
        if (exec.getStatus() != ItemStatus.PENDING || Thread.currentThread().isInterrupted() || cancelRequested) {
            return; // failed, filtered, or stopped
        }
        Optional<String> found;
        try {
            found = resolver.checkDestination(exec);
        } catch (RuntimeException e) {
            exec.setError(e);
            notifier.changed();
            return;
        }
        addWarnings(resolver.drainWarnings());
        if (found.isPresent()) {
            exec.setSkippedByResumePoint(found.get());
            notifier.changed();
            return;
        }
        itemRunner.run(exec, barrierIndex, itemSteps.size());
    }

    /** How far the plan checks the existing targets; from the pipeline, quick without one. */
    void setConflictCheck(ConflictCheck conflictCheck) {
        itemRunner.setConflictCheck(conflictCheck);
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
