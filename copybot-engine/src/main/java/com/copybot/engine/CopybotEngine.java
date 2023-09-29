package com.copybot.engine;

import com.copybot.config.CopybotConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.exception.ActionNotFoundException;
import com.copybot.exception.PluginNotFoundException;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.exception.ActionErrorException;
import com.google.gson.GsonBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    public static void init(CopybotConfig config) {
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

    public static void run(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher) {
        synchronized (executor) {
            if (mainTask != null) {
                throw new IllegalStateException("Engine already running");
            }
        }

        var gson = new GsonBuilder().setPrettyPrinting().create();
        PipelineConfig testPipelineConfig = gson.fromJson("""
                {
                  "inSteps": [
                    {
                      "action": "read.file",
                      "actionConfig" : {
                         "path" : "d:/photos/"
                      }
                    }
                  ],
                  "analyseSteps": [],
                  "outSteps": []
                }
                """, PipelineConfig.class);

        // ---------

        for (var inStep : testPipelineConfig.inSteps()) {

        }
        PipelineStepConfig inStepsConfig = testPipelineConfig.inSteps().get(0);

        PipelineStep<IInAction> inStep;
        try {
            inStep = PluginEngine.resolve(inStepsConfig, IInAction.class);
        } catch (PluginNotFoundException e) {
            throw new RuntimeException(e);
        } catch (ActionNotFoundException e) {
            throw new RuntimeException(e);
        } catch (ActionErrorException e) {
            throw new RuntimeException(e);
        }

        PipelineState state = new PipelineState(java.util.List.of());

        synchronized (executor) {
            mainTask = executor.submit(() -> {
                try {
                    inStep.getAction().listFiles(workItem -> {
                        state.getWorkItems().add(workItem);
                        if (watcher != null) {
                            watcher.accept(state);
                        }
                    });
                } catch (ActionErrorException e) {
                    throw new RuntimeException(e);
                } finally {
                    synchronized (executor) {
                        mainTask = null;
                    }
                }
            });
        }
    }

    public static void waitForCompletion() throws InterruptedException, ExecutionException {
        mainTask.get();
    }
}
