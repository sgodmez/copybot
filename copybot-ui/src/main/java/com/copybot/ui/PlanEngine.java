package com.copybot.ui;

import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.ui.model.PlanOperations;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** The operations of the plan view on the engine of the application ({@link CopybotMainUi#ENGINE}), for one pipeline. */
record PlanEngine(Path pipelinePath) implements PlanOperations.Engine<Plan, Execution> {

    @Override
    public Plan prepare(Consumer<PipelineState> watcher, Consumer<Plan> started, ResumePoint chosen) {
        return CopybotMainUi.ENGINE.prepare(pipelinePath, watcher, started, chosen);
    }

    @Override
    public Execution run(Consumer<PipelineState> watcher, ResumePoint chosen) {
        return CopybotMainUi.ENGINE.run(pipelinePath, watcher, chosen);
    }

    @Override
    public Execution execute(Plan plan, ResumePoint override) {
        return CopybotMainUi.ENGINE.execute(plan, override);
    }

    @Override
    public void analyse(Plan plan) {
        CopybotMainUi.ENGINE.analyse(plan);
    }

    @Override
    public Optional<String> defaultOutPattern(Plan plan) {
        return plan.defaultOutPattern();
    }

    @Override
    public void setOutPattern(Plan plan, List<WorkItemExecution> items, String pattern) {
        plan.setOutPattern(items, pattern);
    }

    @Override
    public void await(Execution run) throws InterruptedException {
        run.await();
    }

    @Override
    public PipelineState planState(Plan plan) {
        return plan.getState();
    }

    @Override
    public List<WorkItemExecution> orderedItems(Plan plan) {
        return plan.getOrderedItems();
    }

    @Override
    public Optional<String> executionRefusal(Plan plan) {
        return plan.executionRefusal();
    }

    @Override
    public void cancelPreparation(Plan plan) {
        plan.cancelPreparation();
    }

    @Override
    public void cancelAnalysis(Plan plan) {
        plan.cancelAnalysis();
    }

    @Override
    public PipelineState runState(Execution run) {
        return run.getState();
    }

    @Override
    public void pause(Execution run) {
        run.pause();
    }

    @Override
    public void resume(Execution run) {
        run.resume();
    }

    @Override
    public void cancel(Execution run) {
        run.cancel();
    }
}
