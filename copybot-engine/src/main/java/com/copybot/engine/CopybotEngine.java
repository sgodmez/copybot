package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
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
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /** Best-effort grace period given to a cancelled pipeline to release its resources. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private static final Path DEFAULT_CONFIG_PATH = Path.of("./config.json");

    /** Used when the config declares no pluginPath; a missing directory simply loads no plugins. */
    private static final Path DEFAULT_PLUGIN_PATH = Path.of("./plugins");

    private final CopybotConfig config;
    private final Path configFile;

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
    /** Guarded by lock. */
    private boolean closed;

    // visible for tests: an engine on an in-memory configuration, the plugins left as they are
    CopybotEngine(CopybotConfig config) {
        this(config, Path.of("config.json"));
    }

    private CopybotEngine(CopybotConfig config, Path configFile) {
        this.config = config;
        this.configFile = configFile;
    }

    /**
     * Reads the configuration (default {@code ./config.json}), makes sure the plugins are loaded (once
     * per JVM) and creates the engine.
     *
     * @throws CopybotException config.not-found / config.not-json
     */
    public static CopybotEngine create(Optional<Path> configPath) {
        Path configFile = configPath.orElse(DEFAULT_CONFIG_PATH);
        CopybotConfig config = readConfig(configFile);
        loadPlugins(config);
        return new CopybotEngine(config, configFile.toAbsolutePath().normalize());
    }

    /** The configuration file read at startup, absolute. */
    public Path configFile() {
        return configFile;
    }

    /** How the plugins of this JVM were loaded (spec plugins-view §1). */
    public PluginReport pluginReport() {
        Path pluginPath = config.pluginPath() != null ? config.pluginPath() : DEFAULT_PLUGIN_PATH;
        List<Path> devPluginPaths = config.devPluginPaths() != null ? List.of(config.devPluginPaths()) : List.of();
        return PluginReports.of(configFile, pluginPath, config.pluginPath() != null, devPluginPaths, PluginEngine.getAllPlugins());
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
        begin();
        MainExecutor mainExecutor = endOnFailure(() -> {
            PipelineConfig pipelineConfig = readPipeline(pipelinePath);
            PipelineChecks.requirePreparable(pipelineConfig);
            MainExecutor prepared = new MainExecutor(pipelineConfig, watcher, newRegistry(), resumeContext(pipelinePath, pipelineConfig));
            prepared.chooseResumePoint(chosen);
            return prepared;
        });
        return prepareBegun(mainExecutor, started);
    }

    /**
     * Continues a stopped preparation ({@link Plan#canContinue()}) from a resume point, without listing again: like
     * a cursor at that point (spec manual-point §2). Blocking, in the calling thread, the active operation for its
     * whole duration (close() stops it); its progress goes to the watcher of the preparation. Ends PREPARED (the plan
     * is then executable), CANCELLED when stopped again ({@link Plan#cancelPreparation()}, still continuable), ERROR
     * on a failure.
     *
     * @throws IllegalStateException another operation is active, the engine is closed, or the plan is not a stopped
     *                               preparation whose listing is complete
     */
    public void continuePreparation(Plan plan, ResumePoint point) {
        begin();
        try {
            MainExecutor mainExecutor = plan.getExecutor();
            track(mainExecutor);
            synchronized (lock) {
                if (closed) { // a stopped plan ignores cancel(): refuse here rather than run while closing
                    throw new IllegalStateException(ResourcesEngine.getString("engine.closed"));
                }
            }
            mainExecutor.continuePreparation(point);
            boolean kept = false;
            if (mainExecutor.getState().getStatus() == PipelineStatus.PREPARED) {
                synchronized (lock) {
                    if (!closed) {
                        preparedPlan = mainExecutor;
                        kept = true;
                    }
                }
            }
            if (!kept) {
                mainExecutor.resume(); // a stopped or failed continuation leaves nothing paused
            }
        } finally {
            end();
        }
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
     *                               is not PREPARED (checked before anything is submitted)
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
     * preparation. Nothing to analyse: returns at once, the plan untouched. On a stopped preparation that can be
     * continued ({@link Plan#canContinue()}), the files asked for ({@link Plan#requestAnalysis}): CANCELLED again
     * when it returns, still continuable (spec manual-point §2).
     *
     * @throws IllegalStateException when another operation is active, the engine is closed or the plan is neither
     *                               PREPARED nor a stopped preparation that can be continued
     */
    public void analyse(Plan plan) {
        begin();
        try {
            MainExecutor mainExecutor = plan.getExecutor();
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
     *                phase. Exceptions it throws are swallowed. It must not call {@link #close()} nor any
     *                other blocking engine operation (the operation notifying it would wait for itself).
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
            PipelineConfig pipelineConfig = readPipeline(pipelinePath);
            PipelineChecks.requireExecutable(pipelineConfig);
            ResumeContext resume = pipelineConfig.resumeMode() == ResumeMode.NONE && chosen == null
                    ? null
                    : resumeContext(pipelinePath, pipelineConfig);
            MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, newRegistry(), resume);
            mainExecutor.chooseResumePoint(chosen);
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
        synchronized (lock) {
            busy = false;
            active = null;
            lock.notifyAll();
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
            Plan plan = new Plan(mainExecutor);
            if (started != null) {
                started.accept(plan);
            }
            mainExecutor.prepare();
            boolean kept = false;
            if (mainExecutor.getState().getStatus() == PipelineStatus.PREPARED) {
                synchronized (lock) {
                    if (!closed) { // otherwise close() already looked for a plan to resume
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
                boolean terminal = status == PipelineStatus.SUCCESS || status == PipelineStatus.ERROR
                        || status == PipelineStatus.CANCELLED;
                if (mainExecutor.isCancelRequested()) {
                    // a failure while cancelling is a consequence of the cancel: never ERROR, but always terminal
                    if (!terminal) {
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

    private static CopybotConfig readConfig(Path configPath) {
        if (!configPath.toFile().canRead()) {
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

    private static void loadPlugins(CopybotConfig config) {
        Path pluginPath = config.pluginPath() != null ? config.pluginPath() : DEFAULT_PLUGIN_PATH;
        // a configured-but-missing dev directory must not break startup: dev paths reach
        // ModuleFinder directly, which throws on nonexistent paths (the main pluginPath is
        // directory-listed first and tolerates absence)
        List<Path> devPluginPaths = config.devPluginPaths() != null && Files.isDirectory(config.devPluginPaths())
                ? List.of(config.devPluginPaths())
                : List.of();
        PluginEngine.load(pluginPath, devPluginPaths); // idempotent: only the first load of the JVM counts
    }

    private ResourceRegistry newRegistry() {
        return new ResourceRegistry(ResourceSettings.from(config));
    }

    private static PipelineConfig readPipeline(Path pipelinePath) {
        if (!Files.isReadable(pipelinePath)) {
            throw CopybotException.ofResource("pipeline.not-found", pipelinePath);
        }
        JsonElement tree;
        try (var reader = Files.newBufferedReader(pipelinePath)) {
            tree = JsonParser.parseReader(reader);
        } catch (IOException | JsonParseException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
        // before Gson: its enum adapter may fail on a non-string mode, which would read as "not a JSON"
        checkResumeModeType(tree);
        JsonElement resume = tree.isJsonObject() ? tree.getAsJsonObject().get("resume") : null;
        checkEnumType(tree, "execution", "execution.unknown");
        checkEnumType(tree, "conflictCheck", "conflict-check.unknown");
        checkEnumType(resume, "destinationCheck", "resume.destination-check.unknown");
        checkEnumType(resume, "destinationMatch", "resume.destination-match.unknown");
        PipelineConfig pipelineConfig;
        try {
            pipelineConfig = GsonUtil.getGson().fromJson(tree, PipelineConfig.class);
        } catch (JsonParseException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
        if (pipelineConfig == null) { // empty file
            throw CopybotException.ofResource("pipeline.not-json", pipelinePath);
        }
        checkResumeModeName(tree, pipelineConfig);
        checkEnumName(tree, "execution", pipelineConfig.execution(), "execution.unknown");
        checkEnumName(tree, "conflictCheck", pipelineConfig.conflictCheck(), "conflict-check.unknown");
        checkEnumName(resume, "destinationCheck",
                pipelineConfig.resume() == null ? null : pipelineConfig.resume().destinationCheck(),
                "resume.destination-check.unknown");
        checkEnumName(resume, "destinationMatch",
                pipelineConfig.resume() == null ? null : pipelineConfig.resume().destinationMatch(),
                "resume.destination-match.unknown");
        return pipelineConfig;
    }

    /** Before Gson, like the resume mode: a present, non-null member that is not a string is an unknown name. */
    private static void checkEnumType(JsonElement object, String member, String resourceKey) {
        if (object == null || !object.isJsonObject()) {
            return;
        }
        JsonElement value = object.getAsJsonObject().get(member);
        if (value == null || value.isJsonNull() || value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return;
        }
        throw CopybotException.ofResource(resourceKey, value.isJsonPrimitive() ? value.getAsString() : value.toString());
    }

    /**
     * A present, non-null member that Gson read as null (an unknown name, or not a string: spec execution-mode §1)
     * would silently mean the default.
     */
    private static void checkEnumName(JsonElement object, String member, Object read, String resourceKey) {
        if (read != null || object == null || !object.isJsonObject()) {
            return;
        }
        JsonElement value = object.getAsJsonObject().get(member);
        if (value != null && !value.isJsonNull()) {
            throw CopybotException.ofResource(resourceKey,
                    value.isJsonPrimitive() ? value.getAsString() : value.toString());
        }
    }

    /** A present, non-null resume mode that is not a string (number, boolean, object, array) is unknown. */
    private static void checkResumeModeType(JsonElement tree) {
        JsonElement mode = resumeMode(tree);
        if (mode == null || mode.isJsonNull() || mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString()) {
            return;
        }
        throw CopybotException.ofResource("resume.mode.unknown",
                mode.isJsonPrimitive() ? mode.getAsString() : mode.toString());
    }

    /** Gson maps an unknown enum name to null, which would silently mean the default mode. */
    private static void checkResumeModeName(JsonElement tree, PipelineConfig pipelineConfig) {
        if (pipelineConfig.resume() == null || pipelineConfig.resume().mode() != null) {
            return;
        }
        JsonElement mode = resumeMode(tree);
        if (mode != null && mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString()) {
            throw CopybotException.ofResource("resume.mode.unknown", mode.getAsString());
        }
    }

    /** The raw {@code resume.mode} element of the pipeline tree, null when absent. */
    private static JsonElement resumeMode(JsonElement tree) {
        if (!tree.isJsonObject()) {
            return null;
        }
        JsonElement resume = tree.getAsJsonObject().get("resume");
        return resume != null && resume.isJsonObject() ? resume.getAsJsonObject().get("mode") : null;
    }

    private static ResumeContext resumeContext(Path pipelinePath, PipelineConfig pipelineConfig) {
        ResumeConfig resume = pipelineConfig.resume();
        return new ResumeContext(pipelineConfig.resumeMode(), ResumeStateStore.forPipeline(pipelinePath),
                resume == null ? null : resume.destinationCheck(), resume == null ? null : resume.destinationMatch());
    }
}
