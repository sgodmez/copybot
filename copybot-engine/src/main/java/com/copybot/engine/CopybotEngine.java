package com.copybot.engine;

import com.copybot.engine.config.CopybotConfig;
import com.copybot.engine.exception.ActionNotFoundException;
import com.copybot.engine.exception.PluginNotFoundException;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.action.IInAction;
import com.copybot.plugin.exception.ActionErrorException;
import com.google.gson.GsonBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class CopybotEngine {

    private static ExecutorService executor;

    public static void init(CopybotConfig config) {
        executor = Executors.newCachedThreadPool();

        //pluginEngine
        PluginEngine.load(Path.of("D:\\plugins2\\"));
    }

    public static void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public static void run(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher) {
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

        PipelineState state = new PipelineState(List.of());

        executor.submit(() -> {
            try {
                inStep.action().listFiles(workItem -> {
                    state.getWorkItems().add(workItem);
                    if (watcher != null) {
                        watcher.accept(state);
                    }
                });
            } catch (ActionErrorException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
