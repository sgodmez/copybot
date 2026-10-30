package com.copybot.engine;

import com.copybot.config.SecretReferences;
import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeResolver;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.engine.resume.ZoneCorrection;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WrittenFile;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
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

    /** The archives of this execution: their work folder lives from the preparation until the end of the copy. */
    private final RunArchives archives;
    /** The pipe of the listings: archives unfolded, items grouped, then {@link #emitItem}. */
    private final ListingPipe listingPipe;
    /** What the engine did with this plan (executed, abandoned). Guarded by the lock of the engine. */
    private CopybotEngine.PlanFate fate = CopybotEngine.PlanFate.OPEN;

    /** The pipeline file, null for an executor built without one (its run report then has an empty name). */
    private final Path pipelinePath;

    /** The directories the out step created, for the run report (spec post-process, run-end). */
    private final Set<Path> createdDirectories = ConcurrentHashMap.newKeySet();
    /** Every file the out step wrote and verified, in the order of the writes, for the run report. */
    private final Queue<WrittenFile> writtenFiles = new ConcurrentLinkedQueue<>();

    /**
     * Unique per pipeline run (prepare and execute share it), handed to the out step: it names its temporary
     * files and recognises the ones a crashed run left behind (spec safe-write §3).
     */
    private final WriteContext writeContext = new WriteContext(UUID.randomUUID().toString(), null,
            createdDirectories::add, (target, sha256, size) -> writtenFiles.add(new WrittenFile(target, sha256, size)));

    /**
     * The end-of-run actions of the pipeline (spec run-end), resolved before the other steps; null until then (an
     * executor built without pipeline has none, unless a test gives them).
     */
    private volatile RunEnd runEnd;
    /** The resolution of {@link #runEnd} was tried (resolved, or failed: the run's failure). Phase thread only. */
    private boolean runEndResolutionTried;
    /** {@link #run()}, {@link #stream()} or {@link #execute}: a run launched to copy, which ends with its actions. */
    private volatile boolean copyRun;
    /**
     * The outcome of the copy run is decided: only its end-of-run actions are left ({@link #finishRun}); cancel()
     * then stops them, nothing else. Guarded by phaseLock.
     */
    private boolean ending;
    /**
     * A copy run ended stopped or failed ({@link #endWith}): its status is not published yet, but cancel() and
     * pause() act as if it were, until {@link #ending}. Guarded by phaseLock.
     */
    private boolean outcomeDecided;
    /** The actions {@link #finishRun} runs, once {@link #ending}. Guarded by phaseLock. */
    private RunEnd endingActions;
    /** The start of the run's first phase (the preparation for a plan), for the run report. */
    private volatile Instant runStarted;
    /** The start of the copy, null until it begins, for the run report. */
    private volatile Instant copyStarted;

    private List<PipelineStep<IInAction>> inSteps;
    /**
     * The steps of {@link #layout}, flat: analyses, secondary outputs, main branch. volatile: {@link Plan#projectionOf}
     * reads it from another thread while the plan is being prepared.
     */
    private volatile List<PipelineStep<?>> itemSteps;
    /** Where the parts of {@link #itemSteps} sit; set before it. */
    private volatile ItemLayout layout;
    /** The secondary outputs with their steps resolved (laid in {@link #itemSteps} too); empty without any. */
    private volatile List<StepResolver.Branch> secondaryBranches = List.of();

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
    /**
     * The items whose time zone was corrected ({@link #setCaptureZone}) since the last complete
     * {@link #analyseDeferred()}: it checks their new target at the destination.
     */
    private final Set<WorkItemExecution> zoneRecheck = ConcurrentHashMap.newKeySet();
    /** The manual resume point in force at the last {@link #setCaptureZone}, null for the proposed one. */
    private volatile ResumePoint recheckOverride;
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
     * With grouping rules, the warning of a streaming pipeline (spec execution-mode §3): streamed folder by folder, or
     * run in the automatic mode. Published with the configuration warnings.
     */
    private volatile List<String> modeWarnings = List.of();
    /** The steps could not be resolved before the phase ({@link #stream()}): thrown again by the phase. */
    private Throwable stepsFailure;
    /** The actions whose {@link IAction#beforeAll} ran in the current phase, in that order. Phase thread only. */
    private final List<IAction> begunActions = new ArrayList<>();

    /**
     * While preparing, true when the resume point comes from the destination probe: the items are not analysed at
     * the listing, the probe analyses the ones it checks, then the point the ones it selects.
     */
    private volatile boolean analysisOnDemand;

    /**
     * Where the own out patterns (spec own-out-pattern §2) and the time zone corrections (spec timezone-correction §3)
     * are kept; null: in memory only.
     */
    private volatile ResumeStateStore outPatternStore;
    /** The own out patterns by item, read at the start of a listing phase; given to the listed items that match. */
    private final Map<ItemKey, String> ownPatterns = new ConcurrentHashMap<>();
    /** The time zone corrections by item, read and given like {@link #ownPatterns}. */
    private final Map<ItemKey, ZoneCorrection> zoneCorrections = new ConcurrentHashMap<>();

    private ResumeResolver resolver;
    private ResumeProposal proposal;
    private List<WorkItemExecution> orderedItems = List.of();

    private final TaskGroup tasks = new TaskGroup();
    private final CoalescingNotifier<PipelineState> notifier;
    private final ItemRunner itemRunner;

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
        this(pipelineConfig, watcher, registry, resume, null);
    }

    /**
     * @param pipelinePath the pipeline file, named in the run report of the end-of-run actions; null when unknown
     * @see #MainExecutor(PipelineConfig, Consumer, ResourceRegistry, ResumeContext)
     */
    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry,
                        ResumeContext resume, Path pipelinePath) {
        this.pipelineConfig = pipelineConfig;
        this.pipelinePath = pipelinePath;
        this.registry = registry;
        this.resume = resume;
        this.state = new PipelineState();
        this.state.setRegistry(registry);
        this.notifier = new CoalescingNotifier<>(watcher, state);
        this.archives = newRunArchives();
        this.listingPipe = newListingPipe(pipelineConfig);
        this.itemRunner = newItemRunner();
        this.itemRunner.setConflictCheck(pipelineConfig.conflictCheckMode());
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
        this.pipelinePath = null;
        this.registry = registry;
        this.resume = resume;
        this.inSteps = inSteps;
        this.layout = ItemLayout.withoutBranches(itemSteps, barrierIndex);
        this.itemSteps = layout.steps();
        this.barrierIndex = barrierIndex;
        this.state = new PipelineState();
        this.state.setRegistry(registry);
        this.notifier = new CoalescingNotifier<>(watcher, state);
        this.archives = newRunArchives();
        this.listingPipe = newListingPipe(null);
        this.itemRunner = newItemRunner();
        this.itemRunner.setProcessWhileListing(processWhileListing);
    }

    // visible for tests: secondary outputs laid between the analyses and the main branch of the pre-resolved steps
    // (once, before the run)
    void setSecondaryBranches(List<StepResolver.Branch> branches) {
        this.secondaryBranches = List.copyOf(branches);
        this.layout = ItemLayout.of(itemSteps, barrierIndex, branches);
        this.itemSteps = layout.steps();
    }

    // visible for tests: the end-of-run actions, instead of the pipeline's
    void setRunEnd(List<RunEnd.Entry> entries) {
        this.runEnd = new RunEnd(entries);
    }

    /**
     * How long the end-of-run actions of a cancelled run may still take ({@link RunEnd#waitBound}): the ones still to
     * run once resolved, the pipeline's otherwise (an action without its time limit counted as one without limit);
     * zero when there is none. Any thread.
     */
    Duration cancelledRunEndWait() {
        RunEnd actions = runEnd;
        if (actions != null) {
            return RunEnd.waitBound(actions.remainingTimeouts(RunOutcome.CANCELLED, state.getRunEndStep()));
        }
        if (pipelineConfig == null) {
            return Duration.ZERO;
        }
        return RunEnd.waitBound(pipelineConfig.runEnd().stream()
                .filter(config -> config.outcomes().contains(RunOutcome.CANCELLED))
                .map(config -> config.timeoutSeconds() == null ? Duration.ZERO : Duration.ofSeconds(config.timeoutSeconds()))
                .toList());
    }

    // visible for tests: the end-of-run actions resolved, empty before
    List<RunEnd.Entry> runEndEntries() {
        RunEnd actions = runEnd;
        return actions == null ? List.of() : actions.entries();
    }

    /** The pipeline file, null when unknown. */
    Path pipelinePath() {
        return pipelinePath;
    }

    // visible for tests: the grouping rules and archives block of an executor built from pre-resolved steps
    void setListingConfig(PipelineConfig config) {
        listingPipe.setConfig(config);
    }

    // visible for tests: where the work folders of the archives are made
    void setArchiveBase(Path base) {
        archives.setBase(base);
    }

    private RunArchives newRunArchives() {
        return new RunArchives(state, notifier::changed, this::stopListing);
    }

    /** @param config where the pipe reads its grouping rules and archives block; null: no pipe */
    private ListingPipe newListingPipe(PipelineConfig config) {
        return new ListingPipe(config, archives, registry, state, notifier::changed, this::emitItem, this::emitFailed,
                () -> cancelRequested);
    }

    /** The archives of this execution, released by the engine when the plan is abandoned. */
    RunArchives archives() {
        return archives;
    }

    /** Called by the engine under its lock. */
    CopybotEngine.PlanFate fate() {
        return fate;
    }

    /** Called by the engine under its lock. */
    void setFate(CopybotEngine.PlanFate fate) {
        this.fate = fate;
    }

    private ItemRunner newItemRunner() {
        return new ItemRunner(registry, state, tasks, notifier, writeContext, () -> layout, this::findOutAction,
                () -> cancelRequested, this::addWarnings);
    }

    /**
     * Without resume: one phase, every step, as before. With resume: {@link #prepare()} then, when the
     * preparation succeeded, {@link #execute(ResumePoint)} with the proposed resume point. A run launched to copy:
     * it ends with the end-of-run actions, also when its preparation fails or is cancelled.
     */
    @Override
    public void run() {
        copyRun = true;
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
     * <p>
     * With grouping rules, the files are processed folder by folder, as each input signals the end of a folder; when
     * an input signals no end of folder, the run falls back to the automatic mode instead ({@link #run()}: every input
     * listed before the first copy), with a warning naming that input.
     */
    public void stream() {
        copyRun = true;
        List<String> silent = inputsWithoutFolderSignal();
        if (silent != null && !silent.isEmpty()) {
            modeWarnings = fallbackWarnings(silent);
            itemRunner.setProcessWhileListing(false);
            run();
            return;
        }
        if (silent != null) {
            modeWarnings = List.of(ResourcesEngine.getString("grouping.streaming-by-folder"));
        }
        streaming = true;
        itemRunner.setProcessWhileListing(true);
        runSinglePhase();
    }

    /**
     * With grouping rules, the inputs that signal no end of folder; null without grouping rules (nothing resolved
     * then), or when the rules or the steps cannot be read: the phase then reports it (a step resolution failure is
     * kept and thrown again by the phase, see {@link #resolveSteps()}).
     */
    private List<String> inputsWithoutFolderSignal() {
        try {
            if (listingPipe.groupingRules().isEmpty()) {
                return null;
            }
        } catch (RuntimeException e) {
            return null; // an invalid rule: refused by the phase before any listing
        }
        try {
            resolveSteps();
        } catch (RuntimeException | Error e) {
            stepsFailure = e;
            return null;
        }
        return ListingPipe.inputsWithoutFolderSignal(listingPipe.config(), inSteps);
    }

    private static List<String> fallbackWarnings(List<String> inputs) {
        return inputs.stream().map(input -> ResourcesEngine.getString("grouping.streaming-fallback", input)).toList();
    }

    /**
     * Stops the pipeline cleanly: lifts a pause (the steps it lets through do not start, the listings emit
     * nothing more), then interrupts the current phase, whose listings and items are interrupted in turn,
     * release their permits and go back to PENDING. The pipeline ends CANCELLED and the resume
     * cursor is not written. A phase that has not started yet ends CANCELLED as soon as it starts; a phase
     * already past its point of no return (all its work done, publishing its outcome) is not affected. Once the
     * outcome of a copy run is decided, it only stops its end-of-run actions: the one under way is interrupted, the
     * next ones are skipped, the outcome stays (spec run-end).
     * Idempotent; no effect once the pipeline has terminated. Callable from any thread.
     */
    public void cancel() {
        synchronized (phaseLock) {
            if (ending) {
                endingActions.cancel();
                return;
            }
            if (cancelRequested || outcomeDecided || isTerminated()) {
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
            if (paused || cancelRequested || outcomeDecided || ending || isTerminated()) {
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
    private PipelineStatus onPhaseInterrupted() {
        stopAfterInterrupt(true);
        return PipelineStatus.CANCELLED;
    }

    /**
     * See {@link #onPhaseInterrupted()}.
     *
     * @param cancelled the pipeline ends CANCELLED (published at once, or by {@link #finishRun} in a copy run); false
     *                  for the analysis of deferred items, the plan staying PREPARED
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
            endWith(PipelineStatus.CANCELLED);
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
     * @return the final status: published at once, or by {@link #finishRun} in a copy run
     */
    private PipelineStatus onPhaseFailed(Throwable failure, boolean duringPreparation) {
        if (cancelRequested) {
            state.recordFailureIfAbsent(failure); // the first cause wins; before the status, like below
            return endWith(PipelineStatus.CANCELLED);
        }
        state.setFailure(failure);
        state.setPreparationFailed(duringPreparation); // before the status, like the cause
        return endWith(PipelineStatus.ERROR);
    }

    /**
     * The final status of a phase: published now, unless the phase belongs to a run launched to copy, whose final
     * status is published by {@link #finishRun} once its end-of-run actions ran (spec run-end, "Déroulé").
     */
    private PipelineStatus endWith(PipelineStatus status) {
        if (!copyRun) {
            state.setStatus(status);
            return status;
        }
        synchronized (phaseLock) {
            // as if the status were published: a later cancel() neither interrupts the end of the phase nor turns
            // the outcome into a stop of its actions (they still run; once they run, a cancel() stops them)
            outcomeDecided = true;
            phaseThread = null;
        }
        return status;
    }

    /**
     * The end of a copy run whose outcome is decided (its cursor saved, its phase stopped or failed): the end-of-run
     * actions of the outcome with the report of the run, then the final status. A {@link #cancel()} from now on only
     * stops the actions; one requested since the point of no return of a run that did not end CANCELLED stops them
     * too. Never throws.
     */
    private void finishRun(PipelineStatus status) {
        RunEnd actions = runEnd;
        if (actions == null && pipelineConfig != null && !runEndResolutionTried) {
            // the run ended before its steps were resolved (e.g. cancelled at once): its actions still run
            runEndResolutionTried = true;
            try {
                actions = new RunEnd(RunEnd.resolve(pipelineConfig.runEnd()));
                runEnd = actions;
            } catch (RuntimeException | Error e) {
                LOGGER.log(System.Logger.Level.WARNING, "the end-of-run actions cannot be resolved", e);
                // the run is not in error for it (its outcome is decided): a warning, like a failed action
                addWarnings(List.of(ResourcesEngine.getString("run-end.unresolved",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getName())));
            }
        }
        if (actions == null) {
            actions = new RunEnd(List.of());
        }
        synchronized (phaseLock) {
            ending = true;
            endingActions = actions;
            if (cancelRequested && status != PipelineStatus.CANCELLED) {
                actions.cancel(); // requested after the point of no return: it can only stop the actions
            }
        }
        // a pending interrupt (an external stop of the phase, already turned into CANCELLED) must not stop the
        // actions of that outcome before they start; one coming while they run does stop them
        boolean interrupted = Thread.interrupted();
        try {
            RunOutcome outcome = switch (status) {
                case SUCCESS -> RunOutcome.SUCCESS;
                case CANCELLED -> RunOutcome.CANCELLED;
                default -> RunOutcome.ERROR;
            };
            RunReport report = RunReports.of(pipelinePath, outcome, runStarted, copyStarted, Instant.now(),
                    state.getWorkItems(), List.copyOf(writtenFiles), createdDirectories, state.getWarnings(),
                    state.getFailure(), getRunId());
            actions.run(outcome, report, new RunEnd.Run() {
                @Override
                public List<String> warnings() {
                    return state.getWarnings();
                }

                @Override
                public void warn(String warning) {
                    addWarnings(List.of(warning));
                }

                @Override
                public void running(String name) {
                    state.setRunEndStep(name);
                    notifier.changed();
                }
            });
        } catch (RuntimeException | Error e) {
            LOGGER.log(System.Logger.Level.WARNING, "the end-of-run actions could not run", e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        state.setStatus(status);
    }

    private void runSinglePhase() {
        synchronized (phaseLock) {
            lastPhaseWasPreparation = false;
        }
        markRunning();
        state.setListingInProgress(true);
        archives.reset();
        runStarted = Instant.now();
        copyStarted = null;
        startPhase();
        boolean preparing = true;
        PipelineStatus finalStatus = null;
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
            copyStarted = Instant.now();
            phaseEnd = itemSteps.size();
            itemRunner.setFinalPhase(true);
            runListings();
            addWarnings(archives.warnings());
            endActions();
            commitPhase();
            // like execute(): the final status is published once, after the cursor save and the end-of-run actions
            PipelineStatus status = resolveFinalStatus();
            if (streamed != null) {
                orderedItems = ResumeResolver.order(listedItems); // the keys are frozen since the listing
                if (!listingPipe.failed() && !saveCursor(streamed.point(), streamed.source())) {
                    status = PipelineStatus.ERROR;
                }
            }
            addWarnings(archives.keptWarnings());
            finalStatus = status;
        } catch (InterruptedException e) {
            finalStatus = onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            finalStatus = onPhaseFailed(e, preparing);
        } finally {
            listingPoint = null;
            state.setListingInProgress(false);
            endActionsQuietly(true);
            if (finalStatus != null) {
                finishRun(finalStatus);
            }
            archives.releaseAtPhaseEnd();
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
        listingPipe.clearStopped();
        state.setListingComplete(false);
        markRunning();
        state.setListingInProgress(true);
        archives.reset(); // a new preparation replaces the plan
        runStarted = Instant.now();
        copyStarted = null;
        startPhase();
        PipelineStatus endStatus = null; // a run launched to copy that ends here: published by finishRun
        try {
            checkCancelled();
            PipelineConfig declared = listingPipe.config();
            if (declared != null && declared.executionMode() == ExecutionMode.STREAMING) {
                // prepared although streaming (its fallback, see stream()): told like the streaming run
                List<String> silent = inputsWithoutFolderSignal();
                if (silent != null && !silent.isEmpty()) {
                    modeWarnings = fallbackWarnings(silent);
                }
            }
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
            addWarnings(archives.warnings());
            commitPhase();
            if (listingPipe.failed()) {
                endStatus = endWith(PipelineStatus.ERROR);
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
            endStatus = onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            endStatus = onPhaseFailed(e, true);
        } finally {
            itemRunner.setDryRunSteps(null);
            listingPoint = null;
            analysisOnDemand = false;
            state.setListingInProgress(false);
            synchronized (phaseLock) {
                preparationStoppable = false;
            }
            endActionsQuietly(true);
            if (copyRun && endStatus != null) {
                finishRun(endStatus); // a run launched to copy ends here: never a preparation of a plan
            }
            PipelineStatus status = state.getStatus();
            if (status != PipelineStatus.PREPARED
                    && !(status == PipelineStatus.CANCELLED && state.isListingComplete())) {
                // failed, or stopped while listing: no copy will read the entries. A preparation stopped after its
                // listing keeps them for its analysis (spec manual-point §2) until the plan is abandoned.
                archives.releaseAtPhaseEnd();
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

    /**
     * @param store where the own out patterns (spec own-out-pattern §2) and the time zone corrections (spec
     *              timezone-correction §3) are read and kept, null: in memory only
     */
    void setOutPatternStore(ResumeStateStore store) {
        outPatternStore = store;
    }

    ResumeStateStore outPatternStore() {
        return outPatternStore;
    }

    /**
     * The own out patterns and the time zone corrections of the state file, read again at the start of a listing
     * phase; without a store, the ones in memory are kept.
     *
     * @throws CopybotException resume.state.invalid, a preparation failure like an unreadable cursor
     */
    private void loadOwnPatterns() {
        ResumeStateStore store = outPatternStore;
        if (store == null) {
            return;
        }
        Map<ItemKey, String> read = store.readOutPatterns();
        Map<ItemKey, ZoneCorrection> corrections = store.readZoneCorrections();
        ownPatterns.clear();
        ownPatterns.putAll(read);
        zoneCorrections.clear();
        zoneCorrections.putAll(corrections);
    }

    /** A listed item whose key has an own out pattern gets it. */
    private void applyOwnPattern(WorkItem workItem) {
        if (!ownPatterns.isEmpty()) {
            ItemKey.of(workItem).map(ownPatterns::get).ifPresent(workItem::setOutPattern);
        }
    }

    /**
     * A listed item whose key has a time zone correction gets its zone keys, as if corrected in this plan: analysed,
     * probed and filed in that zone, with no analysis again.
     */
    private void applyZoneCorrection(WorkItem workItem) {
        if (!zoneCorrections.isEmpty()) {
            ItemKey.of(workItem).map(zoneCorrections::get).ifPresent(correction -> {
                Map<String, Object> raw = workItem.getMetadatas().raw();
                raw.put(WorkItemMetadata.CAPTURE_ZONE, correction.place());
                if (correction.clock() != null) {
                    raw.put(WorkItemMetadata.CAMERA_CLOCK, correction.clock());
                }
            });
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
     * The next {@link #prepare()} belongs to a run launched to copy ({@link CopybotEngine#prepareForCopy}): failed or
     * cancelled, it ends with the end-of-run actions of its outcome, like a preparation run by {@link #run()}.
     */
    void markCopyRun() {
        copyRun = true;
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
        boolean complete = false;
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
            complete = true;
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
            if (complete && !zoneRecheck.isEmpty() && endStatus == PipelineStatus.PREPARED) {
                // a stopped analysis leaves them to the execution, which analyses them before any copy
                recheckTheDestination();
            }
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

    /**
     * The process steps of the main branch (the instances the execution will use): the projection of the plan is the
     * main output's, the secondary outputs take no part in it.
     */
    @SuppressWarnings("unchecked")
    private List<PipelineStep<IProcessAction>> processSteps() {
        List<PipelineStep<IProcessAction>> steps = new ArrayList<>();
        for (PipelineStep<?> step : layout.mainSteps()) {
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
                resolver.apply(proposedPoint(), proposedSource(), orderedItems);
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
     * Corrects the time zone of these analysed items (spec timezone-correction §3), or cancels the correction
     * (place null): the zone keys of their metadata are set or removed, then they are to analyse again
     * ({@link #toAnalyse()}), the skipped ones as if asked for ({@link #requestAnalysis}: they stay skipped).
     * {@link #analyseDeferred()} computes nothing more than their analysis, then checks their new target at the
     * destination ({@link #recheckTheDestination}). Only the items to copy or skipped, already analysed (or deferred
     * by a previous correction: the last one wins). A cancel also reaches the items to copy or skipped not analysed
     * (a correction restored at the listing, before the resume point): their zone keys are removed, nothing more.
     * <p>
     * Kept in the state file like an own out pattern, with its key, before anything changes: a failing write changes
     * nothing; restored at the next listing ({@link #applyZoneCorrection}).
     *
     * @param place    the zone of the place of the capture, null to cancel the correction
     * @param clock    the zone of the camera clock, null for the one of the place
     * @param override the manual resume point in force, null for the proposed one
     * @throws IllegalStateException the plan is not PREPARED (being prepared, executed or already executed)
     * @throws CopybotException      resume.state.write-error: nothing changed
     */
    void setCaptureZone(Collection<WorkItemExecution> items, ZoneId place, ZoneId clock, ResumePoint override) {
        if (state.getStatus() != PipelineStatus.PREPARED) {
            throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", state.getStatus()));
        }
        List<WorkItemExecution> corrected = new ArrayList<>();
        List<WorkItemExecution> unanalysed = new ArrayList<>();
        for (WorkItemExecution item : items) {
            if (item.getStatus() != ItemStatus.PENDING && item.getStatus() != ItemStatus.SKIPPED) {
                continue;
            }
            // not analysed yet: left out, unless a previous correction is what defers it (the last one wins)
            if (!item.isAnalysisDeferred() || zoneRecheck.contains(item)) {
                corrected.add(item);
            } else if (place == null) {
                unanalysed.add(item); // restored at the listing: it could never lose it otherwise
            }
        }
        ZoneCorrection correction = place == null ? null : new ZoneCorrection(place, clock);
        Map<ItemKey, ZoneCorrection> corrections = new HashMap<>(zoneCorrections);
        List<WorkItemExecution> changed = new ArrayList<>(corrected);
        changed.addAll(unanalysed);
        for (WorkItemExecution item : changed) {
            item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem())).ifPresent(key -> {
                if (correction == null) {
                    corrections.remove(key);
                } else {
                    corrections.put(key, correction);
                }
            });
        }
        ResumeStateStore store = outPatternStore;
        if (store != null && !corrections.equals(zoneCorrections)) {
            store.writeZoneCorrections(corrections); // first: a failing write changes nothing
        }
        zoneCorrections.clear();
        zoneCorrections.putAll(corrections);
        for (WorkItemExecution item : unanalysed) {
            item.getWorkItem().getMetadatas().raw().remove(WorkItemMetadata.CAPTURE_ZONE);
            item.getWorkItem().getMetadatas().raw().remove(WorkItemMetadata.CAMERA_CLOCK);
        }
        for (WorkItemExecution item : corrected) {
            Map<String, Object> raw = item.getWorkItem().getMetadatas().raw();
            if (place == null) {
                raw.remove(WorkItemMetadata.CAPTURE_ZONE);
            } else {
                raw.put(WorkItemMetadata.CAPTURE_ZONE, place);
            }
            if (place == null || clock == null) {
                raw.remove(WorkItemMetadata.CAMERA_CLOCK);
            } else {
                raw.put(WorkItemMetadata.CAMERA_CLOCK, clock);
            }
            item.markAnalysisDeferred();
            item.setProjection(null);
            item.setTargetCheck(null);
            if (item.getStatus() == ItemStatus.SKIPPED) {
                requestedAnalysis.add(item);
            }
            zoneRecheck.add(item);
        }
        recheckOverride = override;
    }

    /**
     * Once {@link #analyseDeferred()} analysed the corrected items again ({@link #setCaptureZone}): with the resume
     * point "not at the destination" in force, their new target decides again whether they are imported, like at the
     * preparation. With the dichotomy nothing changes: its verdict is a key, which the correction does not move.
     * Phase thread, before the plan is PREPARED again.
     */
    private void recheckTheDestination() {
        try {
            ResumePoint point = recheckOverride != null ? recheckOverride : proposedPoint();
            ResumeSource source = recheckOverride != null ? ResumeSource.MANUAL : proposedSource();
            if (point.kind() == ResumePoint.Kind.NOT_AT_DESTINATION) {
                resolver.recheck(zoneRecheck);
                resolver.apply(point, source, orderedItems);
            }
        } catch (RuntimeException e) {
            // never in the way of the end of the analysis: the statuses stay as the analysis left them
            LOGGER.log(System.Logger.Level.WARNING, "the destination check of the corrected files failed", e);
        } finally {
            zoneRecheck.clear();
        }
    }

    /**
     * The proposed resume point, as applied again to the items: when every file was checked at the destination
     * and none was found there, "not at the destination" rather than everything, so that a file whose time zone
     * correction gives a target found there is skipped too ({@link #recheckTheDestination}); for the other files
     * both select the same ones.
     */
    private ResumePoint proposedPoint() {
        return everyFileFoundNothing() ? ResumePoint.notAtDestination() : proposal.point();
    }

    private ResumeSource proposedSource() {
        return everyFileFoundNothing() ? ResumeSource.DESTINATION : proposal.source();
    }

    private boolean everyFileFoundNothing() {
        return proposal.point().kind() == ResumePoint.Kind.ALL && resolver.checkedEveryFile();
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
        copyRun = true;
        if (runStarted == null) {
            runStarted = Instant.now();
        }
        markRunning();
        startPhase();
        PipelineStatus finalStatus = null;
        try {
            checkCancelled();
            itemRunner.setFinalPhase(true);
            boolean analysesToo = orderedItems.stream()
                    .anyMatch(exec -> exec.getStatus() == ItemStatus.PENDING && exec.isAnalysisDeferred());
            beginActions(itemSteps.subList(analysesToo ? 0 : barrierIndex, itemSteps.size()));
            copyStarted = Instant.now();
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    // an item skipped at the listing, selected again by a manual point, is analysed first
                    itemRunner.submit(exec, exec.isAnalysisDeferred() ? 0 : barrierIndex, itemSteps.size());
                }
            }
            tasks.awaitCompletion();
            endActions();
            commitPhase(); // a cancelled run never writes the cursor, a later cancel never interrupts its write
            // The final status is published once, after the cursor save and the end-of-run actions: a cursor
            // write failure must never be seen by watchers as SUCCESS followed by ERROR.
            PipelineStatus status = resolveFinalStatus();
            if (!saveCursor(point, source)) {
                status = PipelineStatus.ERROR;
            }
            addWarnings(archives.keptWarnings());
            finalStatus = status;
        } catch (InterruptedException e) {
            finalStatus = onPhaseInterrupted();
        } catch (RuntimeException | Error e) {
            finalStatus = onPhaseFailed(e, false);
        } finally {
            endActionsQuietly(true);
            if (finalStatus != null) {
                finishRun(finalStatus);
            }
            archives.releaseAtPhaseEnd();
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
        inSteps.forEach(step -> addWarnings(warnings, step.getAction(), step.getRedactor()));
        itemSteps.forEach(step -> addWarnings(warnings, step.getAction(), step.getRedactor())); // branches included
        RunEnd actions = runEnd; // resolved before the steps
        if (actions != null) {
            actions.entries().forEach(entry -> addWarnings(warnings, entry.action(), entry.redactor()));
        }
        warnings.addAll(modeWarnings);
        state.setWarnings(warnings);
        warningsCollected = true;
    }

    /** The configuration warnings of the action, without the secret values it was configured with. */
    private static void addWarnings(List<String> warnings, IAction action, SecretReferences.Redactor redactor) {
        List<String> actionWarnings = action.configWarnings();
        if (actionWarnings != null) { // a plugin returning null has nothing to say
            actionWarnings.forEach(warning -> warnings.add(redactor.text(warning)));
        }
    }

    private void resolveStepsIfNeeded() {
        resolveSteps();
        registerStepCapacities();
        collectConfigWarnings();
        PipelineConfig listingConfig = listingPipe.config();
        if (listingConfig != null) { // before any listing, like the other refusals
            PipelineChecks.groupingRefusal(listingConfig, findOutAction()).ifPresent(message -> {
                throw CopybotException.of(message);
            });
        }
    }

    /**
     * Resolves the steps of the pipeline once: all of them, or none when one cannot be resolved. A failure met before
     * the phase (the decision of {@link #stream()}) is thrown again here, inside the phase, without resolving twice.
     * The end-of-run actions are resolved first, and kept: a run failing to resolve another step still runs them
     * (spec run-end, "Configuration").
     */
    private void resolveSteps() {
        Throwable failure = stepsFailure;
        if (failure != null) {
            stepsFailure = null;
            if (failure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) failure;
        }
        if (pipelineConfig != null && runEnd == null) {
            runEndResolutionTried = true; // its failure is the run's: not tried again at the end
            runEnd = new RunEnd(RunEnd.resolve(pipelineConfig.runEnd()));
        }
        if (pipelineConfig != null && inSteps == null) {
            List<PipelineStep<IInAction>> in = StepResolver.in(pipelineConfig);
            List<PipelineStep<?>> steps = StepResolver.itemSteps(pipelineConfig);
            List<StepResolver.Branch> branches = StepResolver.secondary(pipelineConfig);
            barrierIndex = pipelineConfig.analyseSteps() == null ? 0 : pipelineConfig.analyseSteps().size();
            secondaryBranches = branches;
            layout = ItemLayout.of(steps, barrierIndex, branches);
            itemSteps = layout.steps();
            inSteps = in; // last: the marker of steps resolved
        }
    }

    /** Submits every listing and waits for them AND every item they emitted (including forked ones). */
    private void runListings() throws InterruptedException {
        itemRunner.setListingGate(listingPipe.open(inSteps.size()));
        for (PipelineStep<IInAction> inStep : inSteps) {
            tasks.submit(() -> listingPipe.run(inStep));
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
        archives.phaseEnded(); // after the shutdown: no listing nor item reads an archive any more
        notifier.stop(); // includes the final, guaranteed notification
    }

    /** The pipeline configuration, null for an executor built from pre-resolved steps. */
    PipelineConfig pipelineConfig() {
        return pipelineConfig;
    }

    /** The secondary outputs with their steps resolved, empty without any or before the steps are resolved. */
    List<StepResolver.Branch> secondaryBranches() {
        return secondaryBranches;
    }

    /**
     * The out step's action of the main branch (the last step), null when the pipeline has none or its steps are not
     * resolved (yet).
     */
    IOutAction findOutAction() {
        ItemLayout items = layout;
        if (items != null && !items.mainSteps().isEmpty()
                && items.mainSteps().getLast().getAction() instanceof IOutAction out) {
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
        return listingPipe.failed() || anyItemFailed ? PipelineStatus.ERROR : PipelineStatus.SUCCESS;
    }

    /**
     * A listed item that failed in the pipe (e.g. an archive that cannot be unfolded): an item in error, shown with
     * the others, never submitted.
     */
    private void emitFailed(WorkItem workItem, CopybotException cause) {
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
        if (resumeAtTheListing) {
            exec.setResumeKey(ItemKey.of(workItem).orElse(null));
            listedItems.add(exec);
        }
        exec.setError(cause);
        exec.markPrepared(); // nothing more to prepare for it
        state.getWorkItems().add(exec);
        notifier.changed();
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
        applyZoneCorrection(workItem); // likewise
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

    /** The listing calling {@link #emitItem} stops because the phase is being stopped (see {@link ListingPipe#stop}). */
    private PhaseStopped stopListing() {
        return listingPipe.stop();
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
