package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.asynch.RunnableCallback;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class CopybotEngine {

    private static ExecutorService executor;
    private static Future<?> mainTask;

    private static List<PipelineStep<IInAction>> inSteps;
    private static List<PipelineStep<IAnalyzeAction>> analyzeSteps;
    private static List<PipelineStep<IProcessAction>> processSteps;
    private static List<PipelineStep<IOutAction>> outSteps;

    public static void init(Optional<Path> configPathOpt) {
        Path configPath = configPathOpt.orElse(Path.of("./config.json"));

        if (!configPath.toFile().canRead()) {
            throw CopybotException.ofResource("config.not-found", configPath.toAbsolutePath());
        }

        CopybotConfig config;
        try {
            String configString = Files.readString(configPath);
            config = GsonUtil.getGson().fromJson(configString, CopybotConfig.class);
        } catch (IOException | JsonSyntaxException e) {
            throw CopybotException.ofResource(e, "config.not-json", configPath);
        }

        executor = Executors.newCachedThreadPool();

        inSteps = new ArrayList<>();
        analyzeSteps = new ArrayList<>();
        processSteps = new ArrayList<>();
        outSteps = new ArrayList<>();

        //String devPlugins="copybot-plugin/copybot-plugin-optional/copybot-plugin-metadata-extractor/target";
        String devPlugins = "";
        List<Path> devPluginPaths = Arrays.stream(devPlugins.split(";"))
                .filter(Predicate.not(String::isBlank))
                .map(Path::of)
                .collect(Collectors.toList());

        //pluginEngine
        PluginEngine.load(Path.of("D:\\plugins2\\"), devPluginPaths);
    }

    public static void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public static void run(Path pipelinePath, Consumer<PipelineState> watcher) {
        synchronized (executor) {
            if (mainTask != null) {
                throw new IllegalStateException("Engine already running");
            }
        }

        PipelineConfig pipelineConfig;
        try {
            pipelineConfig = GsonUtil.getGson().fromJson(Files.newBufferedReader(pipelinePath), PipelineConfig.class);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json");
        }

        synchronized (executor) {
            mainTask = executor.submit(new RunnableCallback(
                    new MainExecutor(pipelineConfig, watcher),
                    () -> {
                        synchronized (executor) {
                            System.out.println("End main executor " + executor); // TODO
                            mainTask = null;
                        }
                    },
                    t -> {

                    }
            ));
        }
    }

    public static void waitForCompletion() throws InterruptedException, ExecutionException {
        mainTask.get();
    }
}
