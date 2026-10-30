package com.copybot.engine;

import com.copybot.config.AppFolders;
import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginReports;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.engine.resume.ResumeConfig;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.exception.CopybotException;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One Copybot engine: a configuration, the plugins and at most one pipeline operation at a time
 * ({@link #prepare}, {@link #execute} or {@link #run}); several pipelines in parallel need several
 * instances. Closing it cancels what is still running and releases its threads.
 *
 * <pre>{@code
 * try (CopybotEngine engine = CopybotEngine.create(Optional.of(configPath))) {
 *     Plan plan = engine.prepare(pipelinePath, watcher);
 *     Execution run = engine.execute(plan, null);
 *     PipelineStatus status = run.await();
 * }
 * }</pre>
 */
public final class CopybotEngine implements AutoCloseable {

    /** What the engine did with a prepared plan: still open, executed, or abandoned ({@link Plan#discard()}). */
    enum PlanFate { OPEN, EXECUTED, DISCARDED }

    private static final System.Logger LOGGER = System.getLogger(CopybotEngine.class.getName());

    /** Best-effort grace period given to a cancelled pipeline to release its resources. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    /** Replaced when the preferences save the disks ({@link #updateResources}); the plugins keep the first one. */
    private volatile CopybotConfig config;
    private final AppFolders folders;

    /**
     * Runs the executions. Its virtual threads are <em>daemon</em> threads: {@link #close()} waits for
     * them, otherwise the JVM could exit while a file write is still streaming and leave a truncated file.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Object lock = new Object();
    /** An operation (a blocking prepare or a running execution) holds the engine. Guarded by lock. */
    private boolean busy;
    /** The pipeline of the current operation, null while idle or while it is being built. Guarded by lock. */
    private MainExecutor active;
    /**
     * The last plan prepared and not executed yet: close() lifts a pause left on it. Only the last one is
     * tracked: a plan prepared earlier and never executed is forgotten by the next prepare, and a pause
     * left on it is not lifted on close. Guarded by lock.
     */
    private MainExecutor preparedPlan;
    /**
     * The last plan prepared, whatever its outcome: the work folder of its archives lives until it is executed, or
     * abandoned (the next preparation or run replaces it, {@link Plan#discard()}, the engine is closed: spec
     * grouping/archives). Only this plan can be executed. Guarded by lock.
     */
    private MainExecutor lastPlan;
    /**
     * A plan discarded while its own operation (preparation, analysis) runs: the work folder of its archives is
     * removed when that operation ends ({@link #end()}). Guarded by lock.
     */
    private MainExecutor discardedWhileActive;
    /** Guarded by lock. */
    private boolean closed;

    // visible for tests: an engine on an in-memory configuration, the plugins left as they are
    CopybotEngine(CopybotConfig config) {
        this(config, workingDirectory());
    }

    private static AppFolders workingDirectory() {
        Path here = Path.of("").toAbsolutePath();
        return new AppFolders(here, here, here.resolve("config.json"), false);
    }

    // visible for tests
    CopybotEngine(CopybotConfig config, AppFolders folders) {
        this.config = config;
        this.folders = folders;
    }

    /**
     * Same as {@link #create(AppFolders)} on the folders resolved from the config file chosen on the command line
     * (empty: the config file of the data folder).
     *
     * @throws CopybotException config.not-found / config.not-json
     */
    public static CopybotEngine create(Optional<Path> configPath) {
        return create(AppFolders.resolve(configPath));
    }

    /**
     * Reads the configuration, makes sure the plugins of the app folder are loaded (once per JVM) and creates the
     * engine. A missing config file is the defaults, and is not written, unless it was chosen on the command line.
     *
     * @throws CopybotException config.not-found / config.not-json
     */
    public static CopybotEngine create(AppFolders folders) {
        CopybotConfig config = readConfig(folders);
        loadPlugins(folders);
        return new CopybotEngine(config, folders);
    }

    /** The configuration file, absolute, whether it exists or not. */
    public Path configFile() {
        return folders.configFile();
    }

    /**
     * The resource capacities and groups saved by the preferences: they apply from the next plan prepared or run
     * started, without a restart (each one builds its resource registry from them). A run under way keeps its own.
     */
    public void updateResources(Map<String, Integer> resources, List<List<String>> resourceGroups) {
        config = new CopybotConfig(resources, resourceGroups);
    }

    /**
     * The folders the steps of a pipeline read or write (the folder of file.read, the fixed start of the pattern of
     * file.write, those of the plugins), absolute, for the resources window: a disk of the pipeline can be set
     * before it is there. A step that cannot be resolved (plugin missing, invalid configuration) is left out.
     *
     * @throws CopybotException pipeline.not-found / pipeline.not-json
     */
    public List<Path> pipelinePaths(Path pipelinePath) {
        PipelineConfig pipeline = PipelineReader.read(pipelinePath);
        Set<Path> paths = new LinkedHashSet<>();
        addTouchedPaths(paths, pipeline.inSteps(), IInAction.class);
        addTouchedPaths(paths, pipeline.analyseSteps(), IAnalyzeAction.class);
        addTouchedPaths(paths, pipeline.actionSteps(), IProcessAction.class);
        if (pipeline.outStep() != null) {
            addTouchedPaths(paths, List.of(pipeline.outStep()), IOutAction.class);
        }
        return List.copyOf(paths);
    }

    /**
     * The mode a run of the pipeline really takes: its {@code "execution"}, but a streaming pipeline with grouping
     * rules and an input that does not signal the end of its folders runs in the automatic mode (spec
     * execution-mode §3, see {@link MainExecutor#stream()}), its preparation telling why. For the view that starts it.
     *
     * @throws CopybotException pipeline.not-found / pipeline.not-json, an input or a grouping rule that cannot be
     *                          resolved
     */
    public ExecutionMode executionMode(Path pipelinePath) {
        PipelineConfig pipeline = PipelineReader.read(pipelinePath);
        ExecutionMode declared = pipeline.executionMode();
        if (declared != ExecutionMode.STREAMING || ListingPipe.groupingRules(pipeline).isEmpty()
                || ListingPipe.inputsWithoutFolderSignal(pipeline, StepResolver.in(pipeline)).isEmpty()) {
            return declared;
        }
        return ExecutionMode.AUTO;
    }

    private static void addTouchedPaths(Set<Path> paths, List<PipelineStepConfig> steps,
                                        Class<? extends IAction> kind) {
        if (steps == null) {
            return;
        }
        for (PipelineStepConfig step : steps) {
            try {
                for (Path path : PluginEngine.resolve(step, kind).getAction().touchedPaths(null)) {
                    if (!path.toString().isEmpty()) {
                        paths.add(path.toAbsolutePath().normalize());
                    }
                }
            } catch (RuntimeException e) {
                // this step tells nothing (plugin missing, invalid configuration): the others still do; the run
                // reports the failure when it resolves the step
                LOGGER.log(System.Logger.Level.DEBUG, "no paths for the step " + step.getDisplayName(), e);
            }
        }
    }

    /** How the plugins of this JVM were loaded (spec plugins-view §1). */
    public PluginReport pluginReport() {
        return PluginReports.of(folders.configFile(), folders.pluginsDir(), AppFolders.devPluginPaths(),
                PluginEngine.getAllPlugins());
    }

    /**
     * Lists and analyses the pipeline input and resolves the resume point, without writing anything.
     * Blocking, runs in the calling thread (call it outside the UI thread) and counts as the active
     * operation for its whole duration. A failure during the preparation is not thrown: the plan is ERROR
     * with the cause in {@code getState().getFailure()} (decide on the status, not on the failure).
     *
     * @param watcher optional progress observer (see {@link #run}); here its terminal notification is
     *                delivered on the caller's thread, before this method returns. It must not call
     *                {@link #close()} nor any other blocking engine operation.
     * @throws IllegalStateException when another operation is active or the engine is closed
     * @throws CopybotException      when the pipeline file is missing or invalid, or its resume mode unknown
     */
    public Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher) {
        return prepare(pipelinePath, watcher, null);
    }

    /**
     * Same as {@link #prepare(Path, Consumer)}, handing the plan out before it is prepared: its
     * {@link Plan#projectionOf} gives the target of each item as soon as it is analysed.
     *
     * @param started optional, called on the caller's thread once the pipeline file is read, before the
     *                listing. It must not call any engine operation.
     */
    public Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher, Consumer<Plan> started) {
        return prepare(pipelinePath, watcher, started, null);
    }

    /**
     * Same as {@link #prepare(Path, Consumer, Consumer)} from a resume point chosen by the user, which acts like a
     * cursor at that point (spec manual-point §1): the files before it are skipped at the listing without analysis,
     * the destination is not probed, the proposal is the point (MANUAL).
     *
     * @param chosen null for the automatic resume point
     */
    public Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher, Consumer<Plan> started, ResumePoint chosen) {
        return prepare(pipelinePath, watcher, started, chosen, false);
    }

    /**
     * Same as {@link #prepare(Path, Consumer, Consumer, ResumePoint)} for a plan prepared to be executed at once (the
     * CLI with a resume point chosen on its command line): the preparation belongs to a run launched to copy, so a
     * preparation that fails or is cancelled ends with the end-of-run actions of its outcome (spec run-end), like
     * {@link #run}. A plan to look at first (the UI) is prepared with {@link #prepare(Path, Consumer)} instead.
     *
     * @param chosen null for the automatic resume point
     */
    public Plan prepareForCopy(Path pipelinePath, Consumer<PipelineState> watcher, ResumePoint chosen) {
        return prepare(pipelinePath, watcher, null, chosen, true);
    }

    private Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher, Consumer<Plan> started,
                         ResumePoint chosen, boolean forCopy) {
        begin();
        MainExecutor mainExecutor = endOnFailure(() -> {
            PipelineConfig pipelineConfig = PipelineReader.read(pipelinePath);
            PipelineChecks.requirePreparable(pipelineConfig);
            MainExecutor prepared = new MainExecutor(pipelineConfig, watcher, newRegistry(),
                    resumeContext(pipelinePath, pipelineConfig), pipelinePath);
            prepared.setOutPatternStore(ResumeStateStore.forPipeline(pipelinePath)); // whatever the resume mode
            prepared.chooseResumePoint(chosen);
            if (forCopy) {
                prepared.markCopyRun();
            }
            return prepared;
        });
        return prepareBegun(mainExecutor, started);
    }

    // visible for tests: prepares a pipeline built from pre-resolved steps
    Plan prepare(MainExecutor mainExecutor, Consumer<Plan> started) {
        begin();
        return prepareBegun(mainExecutor, started);
    }

    // visible for tests
    Plan prepare(MainExecutor mainExecutor) {
        return prepare(mainExecutor, null);
    }

    /**
     * Executes a prepared plan on a background thread.
     *
     * @param override resume point chosen by the user, null for the proposed one
     * @throws IllegalStateException when another operation is active, the engine is closed or the plan
     *                               is not PREPARED, was discarded ({@link Plan#discard()}) or replaced by a more
     *                               recent preparation or run (checked before anything is submitted)
     */
    public Execution execute(Plan plan, ResumePoint override) {
        begin();
        return endOnFailure(() -> {
            if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
                throw new IllegalStateException(ResourcesEngine.getString("engine.not-prepared", plan.getState().getStatus()));
            }
            plan.executionRefusal().ifPresent(message -> {
                throw CopybotException.of(message);
            });
            MainExecutor mainExecutor = plan.getExecutor();
            synchronized (lock) {
                requireCurrent(mainExecutor);
                mainExecutor.setFate(PlanFate.EXECUTED); // from now on discard() leaves it to its execution
                if (preparedPlan == mainExecutor) {
                    preparedPlan = null; // from now on the execution owns it (and close() cancels it)
                }
            }
            return start(mainExecutor, () -> mainExecutor.execute(override));
        });
    }

    /**
     * Analyses the items of a prepared plan that a manual resume point selected again although their analysis was
     * deferred at the listing ({@link Plan#toAnalyse()}): their target and detail are then known before the
     * execution (spec deferred-analysis §1). Blocking, runs in the calling thread and counts as the active
     * operation for its whole duration; the plan is RUNNING meanwhile, PREPARED again when it returns, also when
     * it is stopped ({@link Plan#cancelAnalysis()}, {@link #close()}). Its progress goes to the watcher of the
     * preparation. Nothing to analyse: returns at once, the plan untouched. On a preparation stopped after its
     * listing ({@link Plan#isStoppedAfterTheListing()}), the files asked for ({@link Plan#requestAnalysis}): CANCELLED
     * again when it returns, still stopped after its listing (spec manual-point §2).
     *
     * @throws IllegalStateException when another operation is active, the engine is closed, the plan was discarded
     *                               or replaced (as for {@link #execute}), or it is neither PREPARED nor a
     *                               preparation stopped after its listing
     */
    public void analyse(Plan plan) {
        begin();
        try {
            MainExecutor mainExecutor = plan.getExecutor();
            synchronized (lock) {
                requireCurrent(mainExecutor); // checked and tracked at once: a discard then waits for the end
                active = mainExecutor;
            }
            track(mainExecutor);
            mainExecutor.analyseDeferred();
        } finally {
            end();
        }
    }

    /**
     * The fully automatic run (the CLI's), on a background thread: with {@code "execution": "streaming"}, each file
     * processed as soon as it is listed ({@link MainExecutor#stream}, spec execution-mode §3); otherwise prepared
     * then executed (a single phase, after the listing, when the pipeline has no resume block). Failures end in the
     * final state, never as an exception of the background thread.
     *
     * @param watcher optional progress observer, invoked from a background thread, at most ~10 times
     *                per second (notifications are coalesced, so the observer sees the latest state
     *                rather than every transition) plus one terminal notification at the end of each
     *                phase. Exceptions it throws are ignored (the first one logged). It must not call
     *                {@link #close()} nor any other blocking engine operation (the operation notifying it would
     *                wait for itself).
     * @throws IllegalStateException when another operation is active or the engine is closed
     * @throws CopybotException      when the pipeline file is missing or invalid, or its resume mode unknown
     */
    public Execution run(Path pipelinePath, Consumer<PipelineState> watcher) {
        return run(pipelinePath, watcher, null);
    }

    /**
     * Same as {@link #run(Path, Consumer)} from a resume point chosen by the user (spec manual-point §1): like a
     * cursor at that point, also without a resume block (the point filters, no cursor is written).
     *
     * @param chosen null for the automatic resume point
     */
    public Execution run(Path pipelinePath, Consumer<PipelineState> watcher, ResumePoint chosen) {
        begin();
        return endOnFailure(() -> {
            PipelineConfig pipelineConfig = PipelineReader.read(pipelinePath);
            PipelineChecks.requireExecutable(pipelineConfig);
            ResumeContext resume = pipelineConfig.resumeMode() == ResumeMode.NONE && chosen == null
                    ? null
                    : resumeContext(pipelinePath, pipelineConfig);
            MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, newRegistry(), resume, pipelinePath);
            mainExecutor.setOutPatternStore(ResumeStateStore.forPipeline(pipelinePath)); // whatever the resume mode
            mainExecutor.chooseResumePoint(chosen);
            replacePlan(null); // the plan, if any, is abandoned
            return start(mainExecutor, pipelineConfig.executionMode() == ExecutionMode.STREAMING
                    ? mainExecutor::stream : mainExecutor::run);
        });
    }

    // visible for tests: runs a pipeline built from pre-resolved steps
    Execution submit(MainExecutor mainExecutor, Runnable task) {
        begin();
        return endOnFailure(() -> start(mainExecutor, task));
    }

    /**
     * Cancels the active operation, lifts a pause left on the last prepared plan (only the last one, see
     * {@code preparedPlan}), waits for the operation then stops the executor, within one grace period of
     * {@value #SHUTDOWN_AWAIT_SECONDS} s for both. A caller already interrupted still waits (its interrupt
     * flag is restored on return): the pipeline keeps its grace period to release its resources.
     * Idempotent. Afterwards every operation is refused.
     */
    @Override
    public void close() {
        MainExecutor toCancel;
        MainExecutor toResume;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            toCancel = active;
            toResume = preparedPlan;
            preparedPlan = null;
        }
        if (toCancel != null) {
            toCancel.cancel(); // lifts its pause too
        }
        if (toResume != null) {
            toResume.resume(); // a plan never executed: nothing stays paused
        }
        // an interrupt already pending would end both waits at once and cut the pipeline's grace period short
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_AWAIT_SECONDS);
        try {
            awaitIdle(deadline);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        abandonPlan();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- one operation at a time ----

    private void begin() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(ResourcesEngine.getString("engine.closed"));
            }
            if (busy) {
                throw new IllegalStateException(ResourcesEngine.getString("engine.busy"));
            }
            busy = true;
        }
    }

    /** Runs the rest of an operation begun with begin(); a failure ends the operation, then is rethrown. */
    private <T> T endOnFailure(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
    }

    /** Publishes the pipeline of the current operation, so that close() can cancel it. */
    private void track(MainExecutor mainExecutor) {
        boolean closing;
        synchronized (lock) {
            active = mainExecutor;
            closing = closed;
        }
        if (closing) {
            mainExecutor.cancel(); // closed while the operation was being set up
        }
    }

    private void end() {
        MainExecutor abandoned;
        synchronized (lock) {
            busy = false;
            // the engine is closed, or the plan discarded meanwhile: nothing reads its archives any more
            abandoned = closed || (active != null && active == discardedWhileActive) ? active : null;
            if (active == discardedWhileActive) {
                discardedWhileActive = null;
            }
            active = null;
            lock.notifyAll();
        }
        if (abandoned != null) {
            abandoned.archives().release();
        }
    }

    /**
     * The plan can be executed or analysed: neither discarded nor replaced by a more recent preparation or run.
     * Called under lock.
     */
    private void requireCurrent(MainExecutor plan) {
        if (plan.fate() == PlanFate.DISCARDED) {
            throw new IllegalStateException(ResourcesEngine.getString("engine.plan.discarded"));
        }
        if (plan != lastPlan) {
            throw new IllegalStateException(ResourcesEngine.getString("engine.plan.replaced"));
        }
    }

    /**
     * See {@link Plan#discard()}: the work folder of its archives is removed now, or when its own operation
     * (preparation, analysis) ends; a pause left on it is lifted. No effect on a plan executed or already discarded.
     */
    void discard(MainExecutor plan) {
        boolean release;
        boolean wasPrepared;
        synchronized (lock) {
            if (plan.fate() != PlanFate.OPEN) {
                return;
            }
            plan.setFate(PlanFate.DISCARDED);
            if (lastPlan == plan) {
                lastPlan = null;
            }
            wasPrepared = preparedPlan == plan;
            if (wasPrepared) {
                preparedPlan = null;
            }
            release = !(busy && active == plan);
            if (!release) {
                discardedWhileActive = plan;
            }
        }
        if (wasPrepared) {
            plan.resume(); // a plan never executed: nothing stays paused
        }
        if (release) {
            plan.archives().release();
        }
    }

    /** @param deadline a {@link System#nanoTime()} instant */
    private void awaitIdle(long deadline) throws InterruptedException {
        synchronized (lock) {
            while (busy) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
        }
    }

    /** begin() was called; end() is called once the preparation is over. */
    private Plan prepareBegun(MainExecutor mainExecutor, Consumer<Plan> started) {
        try {
            track(mainExecutor);
            replacePlan(mainExecutor);
            Plan plan = new Plan(mainExecutor, this);
            if (started != null) {
                started.accept(plan);
            }
            mainExecutor.prepare();
            boolean kept = false;
            if (mainExecutor.getState().getStatus() == PipelineStatus.PREPARED) {
                synchronized (lock) {
                    // closed: close() already looked for a plan to resume; discarded meanwhile: nothing to resume
                    if (!closed && mainExecutor.fate() == PlanFate.OPEN) {
                        preparedPlan = mainExecutor;
                        kept = true;
                    }
                }
            }
            if (!kept) {
                mainExecutor.resume(); // a failed, cancelled or orphan preparation leaves nothing paused
            }
            return plan;
        } finally {
            end();
        }
    }

    /**
     * The plan prepared before {@code next} is abandoned: the work folder of its archives is removed (an executed
     * plan already removed it).
     */
    private void replacePlan(MainExecutor next) {
        MainExecutor previous;
        synchronized (lock) {
            previous = lastPlan;
            lastPlan = next;
        }
        if (previous != null && previous != next) {
            previous.archives().release();
        }
    }

    /**
     * On close, the last plan prepared is abandoned: the work folder of its archives is removed, unless its operation
     * is still running (the grace period of close() is over): it is then removed when that operation ends
     * ({@link #end()}), never while it may still read its archives.
     */
    private void abandonPlan() {
        MainExecutor previous;
        synchronized (lock) {
            previous = lastPlan;
            lastPlan = null;
            if (busy && previous == active) {
                previous = null;
            }
        }
        if (previous != null) {
            previous.archives().release();
        }
    }

    /** Runs the task on the engine executor; begin() was called, end() is called when the task is over. */
    private Execution start(MainExecutor mainExecutor, Runnable task) {
        Execution execution = new Execution(mainExecutor);
        track(mainExecutor);
        executor.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                // MainExecutor reports pipeline failures in its state; this is only a safety net
                PipelineState state = mainExecutor.getState();
                state.recordFailureIfAbsent(e);
                PipelineStatus status = state.getStatus();
                if (mainExecutor.isCancelRequested()) {
                    // a failure while cancelling is a consequence of the cancel: never ERROR, but always terminal
                    if (!status.isTerminal()) {
                        state.setStatus(PipelineStatus.CANCELLED);
                    }
                } else if (status != PipelineStatus.CANCELLED) {
                    state.setStatus(PipelineStatus.ERROR);
                }
            } finally {
                mainExecutor.resume(); // a terminated pipeline leaves nothing paused
                end(); // before markDone(): once await() returns the engine accepts the next operation
                execution.markDone();
            }
        });
        return execution;
    }

    // ---- reading ----

    private static CopybotConfig readConfig(AppFolders folders) {
        Path configPath = folders.configFile();
        if (!configPath.toFile().canRead()) {
            if (!folders.configChosen() && Files.notExists(configPath)) {
                return new CopybotConfig(null, null); // nothing written yet: the defaults
            }
            throw CopybotException.ofResource("config.not-found", configPath.toAbsolutePath());
        }
        CopybotConfig config;
        try {
            config = GsonUtil.getGson().fromJson(Files.readString(configPath), CopybotConfig.class);
        } catch (IOException | JsonSyntaxException e) {
            throw CopybotException.ofResource(e, "config.not-json", configPath);
        }
        if (config == null) { // empty file
            throw CopybotException.ofResource("config.not-json", configPath);
        }
        return config;
    }

    private static void loadPlugins(AppFolders folders) {
        // a missing dev directory must not break startup: dev paths reach ModuleFinder directly, which throws on
        // nonexistent paths (the plugins folder is directory-listed first and tolerates absence)
        List<Path> devPluginPaths = AppFolders.devPluginPaths().stream().filter(Files::isDirectory).toList();
        PluginEngine.load(folders.pluginsDir(), devPluginPaths); // idempotent: only the first load of the JVM counts
    }

    private ResourceRegistry newRegistry() {
        return new ResourceRegistry(ResourceSettings.from(config));
    }

    private static ResumeContext resumeContext(Path pipelinePath, PipelineConfig pipelineConfig) {
        ResumeConfig resume = pipelineConfig.resume();
        return new ResumeContext(pipelineConfig.resumeMode(), ResumeStateStore.forPipeline(pipelinePath),
                resume == null ? null : resume.destinationCheck(), resume == null ? null : resume.destinationMatch());
    }
}
