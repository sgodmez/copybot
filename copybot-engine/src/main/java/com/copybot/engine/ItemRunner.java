package com.copybot.engine;

import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs the steps of one work item: its resources acquired step by step, the lifecycle calls of each action around
 * the step, the forks of a process step submitted as items of their own, the target checked once the item is
 * analysed. {@link MainExecutor} decides which steps a phase runs and sets the phase settings below.
 */
final class ItemRunner {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(ItemRunner.class);

    private final ResourceRegistry registry;
    private final PipelineState state;
    private final TaskGroup tasks;
    private final CoalescingNotifier<PipelineState> notifier;
    private final WriteContext writeContext;
    /** The item steps of the pipeline, null until they are resolved. */
    private final Supplier<List<PipelineStep<?>>> itemSteps;
    /** The out step's action, null when the pipeline has none. */
    private final Supplier<IOutAction> outAction;
    /** A cancel() of the pipeline took effect. */
    private final BooleanSupplier cancelRequested;

    private boolean processWhileListing;
    /** Released once every listing is over: without processing while listing, the items wait for it. */
    private CountDownLatch listingGate;

    /**
     * False while preparing: an item then ends PENDING even when no step follows the barrier, so that the
     * resume point decides whether it is selected (an item ended DONE would escape the resume point).
     */
    private volatile boolean finalPhase;

    /**
     * While preparing, the process steps dry-run for each item as soon as it is analysed (null otherwise):
     * the targets show up during the preparation, not only at its end.
     */
    private volatile List<PipelineStep<IProcessAction>> dryRunSteps;
    /** How far the plan checks the existing targets (spec conflict-check §1). */
    private volatile ConflictCheck conflictCheck = ConflictCheck.QUICK;

    ItemRunner(ResourceRegistry registry, PipelineState state, TaskGroup tasks, CoalescingNotifier<PipelineState> notifier,
               WriteContext writeContext, Supplier<List<PipelineStep<?>>> itemSteps, Supplier<IOutAction> outAction,
               BooleanSupplier cancelRequested) {
        this.registry = registry;
        this.state = state;
        this.tasks = tasks;
        this.notifier = notifier;
        this.writeContext = writeContext;
        this.itemSteps = itemSteps;
        this.outAction = outAction;
        this.cancelRequested = cancelRequested;
    }

    void setProcessWhileListing(boolean processWhileListing) {
        this.processWhileListing = processWhileListing;
    }

    void setListingGate(CountDownLatch listingGate) {
        this.listingGate = listingGate;
    }

    void setFinalPhase(boolean finalPhase) {
        this.finalPhase = finalPhase;
    }

    void setDryRunSteps(List<PipelineStep<IProcessAction>> dryRunSteps) {
        this.dryRunSteps = dryRunSteps;
    }

    /** How far the plan checks the existing targets; from the pipeline, quick without one. */
    void setConflictCheck(ConflictCheck conflictCheck) {
        this.conflictCheck = conflictCheck;
    }

    /** Submits the steps [fromStep, toStep) of this item as a task of the phase. */
    void submit(WorkItemExecution exec, int fromStep, int toStep) {
        long ticket = registry.ticket(); // its place among the resource waiters: the order of submission
        tasks.submit(() -> run(exec, fromStep, toStep, ticket));
    }

    /**
     * Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING, an item the
     * out step skipped ends SKIPPED with its reason.
     */
    void run(WorkItemExecution exec, int fromStep, int toStep) {
        run(exec, fromStep, toStep, registry.ticket());
    }

    /** @param ticket the item's place among the resource waiters ({@link ResourceRegistry#ticket()}) */
    private void run(WorkItemExecution exec, int fromStep, int toStep, long ticket) {
        try {
            if (!processWhileListing) {
                listingGate.await();
            }
            List<PipelineStep<?>> steps = itemSteps.get();
            StepOutcome outcome = StepOutcome.CONTINUE;
            for (int i = fromStep; i < toStep; i++) {
                PipelineStep<?> step = steps.get(i);
                Set<String> footprint = FootprintResolver.resolve(step.getAction(), exec.getWorkItem(), step.getConfig(), i);
                exec.setWaitingResources(i, footprint);
                notifier.changed();
                registry.acquireAll(footprint, ticket);
                if (cancelRequested.getAsBoolean()) {
                    // cancel() lifts a pause before the tasks are shut down: the waiters it grants must not
                    // start their step
                    registry.releaseAll(footprint);
                    throw new InterruptedException("cancelled");
                }
                exec.setRunning(i);
                notifier.changed();
                try {
                    outcome = runStepOfItem(exec, step, i, toStep);
                } finally {
                    registry.releaseAll(footprint);
                }
                if (!outcome.continueItem()) {
                    break;
                }
            }
            if (outcome.skipReason() != null) {
                exec.setSkipped(outcome.skipReason());
            } else if (!outcome.continueItem() || (toStep == steps.size() && finalPhase)) {
                exec.setDone();
            } else {
                exec.setReady();
                if (!finalPhase) {
                    exec.markPrepared(); // stopped at the preparation barrier: analysed
                    projectEarly(exec, ticket);
                }
            }
        } catch (InterruptedException | PhaseStopped e) {
            // PhaseStopped: a fork rejected because the phase is being stopped, an interrupt, not an item failure
            exec.setReady(); // not left WAITING_RESOURCES / RUNNING: the item is simply not done
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (cancelRequested.getAsBoolean()) {
                // a failure caused by the cancel (e.g. a ClosedByInterruptException wrapped by the step) is an
                // interruption like the ones above, not an item error
                // the cause (t) is intentionally dropped: the item is an interruption and the run ends CANCELLED
                exec.setReady();
            } else {
                exec.setError(t);
                exec.propagateFailureToAncestors(); // a failed fork holds the resume cursor back before its parent
                if (!finalPhase) {
                    exec.markPrepared(); // failed before the barrier: nothing more to prepare for it
                }
            }
        } finally {
            notifier.changed();
        }
    }

    /**
     * While preparing, the target of an item is known as soon as it is analysed (spec pattern-helper §4.3), and
     * checked at once (spec conflict-check §2).
     */
    private void projectEarly(WorkItemExecution exec, long ticket) throws InterruptedException {
        List<PipelineStep<IProcessAction>> steps = dryRunSteps;
        if (steps != null) {
            exec.setProjection(DryRunner.project(exec.getWorkItem(), steps));
            checkTarget(exec, ticket); // its own place in the queue: right after its analysis, not after all of them
        }
    }

    /**
     * What the out step would find at the targets of a file to copy (spec conflict-check §4): each produced item is
     * checked, the most severe result kept. Under the disks of the out step's targets (not the source disk), with
     * the file's ticket so that it follows its analysis. A plugin failure is UNKNOWN, never an item error. Nothing
     * for conflictCheck "none", a file not selected, or a dry run that projected nothing.
     */
    void checkTarget(WorkItemExecution exec, long ticket) throws InterruptedException {
        ConflictCheck level = conflictCheck;
        IOutAction out = outAction.get();
        if (level == ConflictCheck.NONE || out == null || exec.getStatus() != ItemStatus.PENDING
                || !(exec.getProjection() instanceof Projection.Projected projected)) {
            return;
        }
        exec.setTargetCheckPending(true); // the view tells the checks still queued once every file is analysed
        notifier.changed();
        try {
            // the destination only: the check reads nothing on the source, it must not hold the card the analyses read
            Set<String> footprint = FootprintResolver.forTargets(out, exec.getWorkItem());
            registry.acquireAll(footprint, ticket);
            try {
                if (cancelRequested.getAsBoolean()) {
                    throw new InterruptedException("cancelled");
                }
                TargetCheck result = null;
                for (WorkItem produced : projected.items()) {
                    result = TargetCheck.mostSevere(result, check(out, produced, level == ConflictCheck.FULL));
                }
                exec.setTargetCheck(result == null ? TargetCheck.UNKNOWN : result);
            } finally {
                registry.releaseAll(footprint);
            }
        } finally {
            exec.setTargetCheckPending(false);
            notifier.changed();
        }
    }

    private static TargetCheck check(IOutAction out, WorkItem produced, boolean compareContent) {
        try {
            TargetCheck check = out.checkTarget(produced, compareContent);
            return check == null ? TargetCheck.UNKNOWN : check;
        } catch (RuntimeException e) {
            LOG.debug(e, "plan.check.failed", produced.getNameDisplay(), String.valueOf(e));
            return TargetCheck.UNKNOWN;
        }
    }

    /**
     * How an item goes on after one step.
     *
     * @param continueItem false when the item stops here
     * @param skipReason   non null when the out step skipped the item: it ends SKIPPED with this reason
     */
    private record StepOutcome(boolean continueItem, String skipReason) {
        static final StepOutcome CONTINUE = new StepOutcome(true, null);
        /** Filtered out by a process step: the item ends DONE. */
        static final StepOutcome FILTERED = new StepOutcome(false, null);

        static StepOutcome skipped(String reason) {
            return new StepOutcome(false, reason);
        }
    }

    /**
     * One step of one item, with what the action gets around it: {@link IAction#beforeEach} and
     * {@link IAction#afterEach} (a failure of either fails the item, like a failure of the step), and the progress
     * channel of the item ({@link ItemProgress#current()}, {@link WriteContext#progress()}): its reports reach the
     * watcher through {@link WorkItemExecution#getWorkStatus()}.
     */
    private StepOutcome runStepOfItem(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep) {
        IAction action = step.getAction();
        ItemProgress progress = status -> {
            exec.reportProgress(stepIndex, status);
            notifier.changed();
        };
        return ItemProgress.callWith(progress, () -> {
            WorkItem item = exec.getWorkItem();
            action.beforeEach(item);
            StepOutcome outcome;
            try {
                outcome = runStep(exec, step, stepIndex, toStep, progress);
            } catch (Throwable t) {
                try {
                    action.afterEach(item);
                } catch (Throwable after) {
                    t.addSuppressed(after); // the failure of the step is the one to report
                }
                throw t;
            }
            action.afterEach(item);
            return outcome;
        });
    }

    /**
     * @param toStep exclusive end of the current phase: a forked item stops at the same step as its parent
     */
    private StepOutcome runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep,
                                ItemProgress progress) {
        IAction action = step.getAction();
        WorkItem item = exec.getWorkItem();
        if (action instanceof IAnalyzeAction analyze) {
            analyze.doAnalyze(item);
            return StepOutcome.CONTINUE;
        }
        if (action instanceof IProcessAction process) {
            List<WorkItem> produced = process.doProcess(item);
            if (produced == null || produced.isEmpty()) {
                return StepOutcome.FILTERED;
            }
            produced.forEach(child -> DryRunner.inheritOutPattern(item, child));
            exec.replaceWorkItem(produced.get(0));
            for (int i = 1; i < produced.size(); i++) {
                WorkItemExecution forked = new WorkItemExecution(produced.get(i), itemSteps.get());
                forked.setParent(exec);
                state.getWorkItems().add(forked);
                notifier.changed();
                try {
                    submit(forked, stepIndex + 1, toStep);
                } catch (RejectedExecutionException e) {
                    if (tasks.isShutdown()) {
                        throw new PhaseStopped(); // the phase shut its executor down (cancel, interrupt)
                    }
                    throw e;
                }
            }
            return StepOutcome.CONTINUE;
        }
        if (action instanceof IOutAction out) {
            WriteResult result = out.write(item, writeContext.withProgress(progress));
            if (result != null && result.isSkipped()) {
                return StepOutcome.skipped(result.reason()); // the steps after it, if any, do not run
            }
            return StepOutcome.CONTINUE;
        }
        throw new UnsupportedOperationException("Unsupported action type: " + action.getClass());
    }
}
