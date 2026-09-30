package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeStateStore;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.exception.CopybotException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class CopybotEngine {

    /** Best-effort grace period given to a cancelled pipeline to release its resources. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    /** Used when the config declares no pluginPath; a missing directory simply loads no plugins. */
    private static final Path DEFAULT_PLUGIN_PATH = Path.of("./plugins");

    private static ExecutorService executor;
    private static Future<?> mainTask;

    private static CopybotConfig config;

    /** PluginEngine.load is not re-entrant: load the plugins once per JVM. */
    private static boolean pluginsLoaded;

    public static void init(Optional<Path> configPathOpt) {
        Path configPath = configPathOpt.orElse(Path.of("./config.json"));

        if (!configPath.toFile().canRead()) {
            throw CopybotException.ofResource("config.not-found", configPath.toAbsolutePath());
        }

        try {
            String configString = Files.readString(configPath);
            config = GsonUtil.getGson().fromJson(configString, CopybotConfig.class);
        } catch (IOException | JsonSyntaxException e) {
            throw CopybotException.ofResource(e, "config.not-json", configPath);
        }

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }

        Path pluginPath = config.pluginPath() != null ? config.pluginPath() : DEFAULT_PLUGIN_PATH;
        // a configured-but-missing dev directory must not break startup: dev paths reach
        // ModuleFinder directly, which throws on nonexistent paths (the main pluginPath is
        // directory-listed first and tolerates absence)
        List<Path> devPluginPaths = config.devPluginPaths() != null && Files.isDirectory(config.devPluginPaths())
                ? List.of(config.devPluginPaths())
                : List.of();
        if (!pluginsLoaded) {
            PluginEngine.load(pluginPath, devPluginPaths);
            pluginsLoaded = true;
        }
    }

    /**
     * Cancels a running pipeline and waits briefly for it to unwind. The wait is not cosmetic:
     * pipeline tasks run on <em>daemon</em> virtual threads, so returning immediately lets the JVM
     * exit while a file write is still streaming and leave a truncated output file behind.
     */
    public static void destroy() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Starts the pipeline on a background thread and returns immediately;
     * use {@link #waitForCompletion()} to join it.
     *
     * @param watcher optional progress observer, invoked from a background thread, at most ~10 times
     *                per second (notifications are coalesced, so the observer sees the latest state
     *                rather than every transition) plus one final notification once the run has
     *                terminated. Exceptions it throws are swallowed.
     */
    public static void run(Path pipelinePath, Consumer<PipelineState> watcher) {
        PipelineConfig pipelineConfig = readPipeline(pipelinePath);
        ResumeContext resume = pipelineConfig.resumeMode() == ResumeMode.NONE
                ? null
                : resumeContext(pipelinePath, pipelineConfig);

        ResourceRegistry registry = new ResourceRegistry(ResourceSettings.from(config));
        MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, registry, resume);

        submit(mainExecutor::run);
    }

    /**
     * Lists and analyses the pipeline input and resolves the resume point, without writing anything.
     * Blocking, runs in the calling thread.
     */
    public static Plan prepare(Path pipelinePath, Consumer<PipelineState> watcher) {
        PipelineConfig pipelineConfig = readPipeline(pipelinePath);
        ResourceRegistry registry = new ResourceRegistry(ResourceSettings.from(config));
        MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, registry, resumeContext(pipelinePath, pipelineConfig));
        mainExecutor.prepare();
        return new Plan(mainExecutor);
    }

    /**
     * Executes a prepared plan on a background thread; join it with {@link #waitForCompletion()}.
     *
     * @param override resume point chosen by the user, null for the proposed one
     * @throws IllegalStateException when the plan is not PREPARED (checked before anything is submitted)
     */
    public static void execute(Plan plan, ResumePoint override) {
        if (plan.getState().getStatus() != PipelineStatus.PREPARED) {
            throw new IllegalStateException("Pipeline is not prepared: " + plan.getState().getStatus());
        }
        submit(() -> plan.getExecutor().execute(override));
    }

    private static void submit(Runnable task) {
        synchronized (CopybotEngine.class) {
            if (mainTask != null) {
                throw new IllegalStateException("Engine already running");
            }
            mainTask = executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (CopybotEngine.class) {
                        mainTask = null;
                    }
                }
            });
        }
    }

    private static PipelineConfig readPipeline(Path pipelinePath) {
        JsonElement tree;
        PipelineConfig pipelineConfig;
        try (var reader = Files.newBufferedReader(pipelinePath)) {
            tree = JsonParser.parseReader(reader);
            pipelineConfig = GsonUtil.getGson().fromJson(tree, PipelineConfig.class);
        } catch (IOException | JsonParseException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
        if (pipelineConfig == null) { // empty file
            throw CopybotException.ofResource("pipeline.not-json", pipelinePath);
        }
        checkResumeMode(tree, pipelineConfig);
        return pipelineConfig;
    }

    /** Gson maps an unknown enum name to null, which would silently mean the default mode. */
    private static void checkResumeMode(JsonElement tree, PipelineConfig pipelineConfig) {
        if (pipelineConfig.resume() == null || pipelineConfig.resume().mode() != null) {
            return;
        }
        JsonElement resume = tree.getAsJsonObject().get("resume");
        JsonElement mode = resume != null && resume.isJsonObject() ? resume.getAsJsonObject().get("mode") : null;
        if (mode != null && mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString()) {
            throw CopybotException.ofResource("resume.mode.unknown", mode.getAsString());
        }
    }

    private static ResumeContext resumeContext(Path pipelinePath, PipelineConfig pipelineConfig) {
        return new ResumeContext(pipelineConfig.resumeMode(), ResumeStateStore.forPipeline(pipelinePath));
    }
    public static void waitForCompletion() throws InterruptedException, ExecutionException {
        Future<?> task;
        synchronized (CopybotEngine.class) {
            task = mainTask;
        }
        if (task != null) {
            task.get();
        }
    }
}
