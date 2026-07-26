package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resources.ResourceSettings;
import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class CopybotEngine {

    /** Best-effort grace period given to a cancelled pipeline to release its resources. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private static ExecutorService executor;
    private static Future<?> mainTask;

    private static CopybotConfig config;

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

        executor = Executors.newVirtualThreadPerTaskExecutor();

        //String devPlugins="copybot-plugin/copybot-plugin-optional/copybot-plugin-metadata-extractor/target";
        String devPlugins = "";
        List<Path> devPluginPaths = Arrays.stream(devPlugins.split(";"))
                .filter(Predicate.not(String::isBlank))
                .map(Path::of)
                .collect(Collectors.toList());

        //pluginEngine
        PluginEngine.load(Path.of("D:\\plugins2\\"), devPluginPaths);
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
        PipelineConfig pipelineConfig;
        try {
            pipelineConfig = GsonUtil.getGson().fromJson(Files.newBufferedReader(pipelinePath), PipelineConfig.class);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json");
        }

        ResourceRegistry registry = new ResourceRegistry(ResourceSettings.from(config));
        MainExecutor mainExecutor = new MainExecutor(pipelineConfig, watcher, registry);

        synchronized (CopybotEngine.class) {
            if (mainTask != null) {
                throw new IllegalStateException("Engine already running");
            }
            mainTask = executor.submit(() -> {
                try {
                    mainExecutor.run();
                } finally {
                    synchronized (CopybotEngine.class) {
                        mainTask = null;
                    }
                }
            });
        }
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
