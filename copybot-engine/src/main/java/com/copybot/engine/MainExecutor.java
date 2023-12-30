package com.copybot.engine;

import com.copybot.engine.pipeline.*;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.action.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class MainExecutor implements Runnable {

    private PipelineConfig pipelineConfig;
    private Consumer<PipelineState> watcher;

    private List<PipelineStep<IInAction>> inPipelineSteps;
    private List<PipelineStep> initialPipelineSteps;

    private int wiListingInProgress;

    private PipelineState state;


    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher) {
        this.pipelineConfig = pipelineConfig;
        this.watcher = watcher;
        this.wiListingInProgress = 0;
        this.state = new PipelineState(java.util.List.of());
    }

    @Override
    public void run() {
        inPipelineSteps = doResolveStep(pipelineConfig.inSteps(), IInAction.class);
        initialPipelineSteps = resolveOtherSteps(pipelineConfig);

        doList();
    }

    private void doList() {
        state.setListingInProgress(true);
        // TODO : introduce ressourcegroup to do in parallel? (can be automatic or manual via a string in config ?)
        for (PipelineStep<IInAction> inStep : inPipelineSteps) {
            inStep.getAction().listFiles(workItem -> {
                WorkItemExecution wie = new WorkItemExecution(workItem, initialPipelineSteps);
                state.getWorkItems().add(wie);
                if (watcher != null) {
                    watcher.accept(state);
                }
                //doNext(); // TODO : evol to permit start processing while listing
            });
        }
        state.setListingInProgress(false);
        doNext();
    }

    private static List<PipelineStep> resolveOtherSteps(PipelineConfig pipelineConfig) {
        int size = 1; // 1 for output
        size += pipelineConfig.analyseSteps() == null ? 0:pipelineConfig.analyseSteps().size();
        size += pipelineConfig.actionSteps() == null ? 0:pipelineConfig.actionSteps().size();
        List<PipelineStep> steps = new ArrayList<>(size);

        steps.addAll(doResolveStep(pipelineConfig.analyseSteps(), IAnalyzeAction.class));
        steps.addAll(doResolveStep(pipelineConfig.actionSteps(), IProcessAction.class));
        // TODO: steps.add(PluginEngine.resolve(pipelineConfig.outStep(), IOutAction.class));
        return Collections.unmodifiableList(steps);
    }

    private static <A extends IAction> List<PipelineStep<A>> doResolveStep(List<PipelineStepConfig> stepConfigs, Class<A> actionClass) {
        if (stepConfigs == null || stepConfigs.isEmpty()) {
            return List.of();
        }

        List<PipelineStep<A>> steps = new ArrayList<>(stepConfigs.size());
        for (PipelineStepConfig inStepConfig : stepConfigs) {
            steps.add(PluginEngine.resolve(inStepConfig, actionClass));
        }
        return Collections.unmodifiableList(steps);
    }

    void doNext() {

    }
}
