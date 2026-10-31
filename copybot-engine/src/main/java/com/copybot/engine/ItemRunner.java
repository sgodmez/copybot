package com.copybot.engine;

import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.SecondaryOutputConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.exception.CopybotException;
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
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs the steps of one work item: its resources acquired step by step, the lifecycle calls of each action around
 * the step, the forks of a process step submitted as items of their own, the secondary outputs run inline on copies
 * of the item, the target checked once the item is analysed. {@link MainExecutor} decides which steps a phase runs and
 * sets the phase settings below.
 */
final class ItemRunner {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(ItemRunner.class);

    private final ResourceRegistry registry;
    private final PipelineState state;
    private final TaskGroup tasks;
    private final CoalescingNotifier<PipelineState> notifier;
    private final WriteContext writeContext;
    /** The item steps of the pipeline and where the secondary outputs sit among them, null until they are resolved. */
    private final Supplier<ItemLayout> layout;
    /** The out step's action, null when the pipeline has none. */
    private final Supplier<IOutAction> outAction;
    /** A cancel() of the pipeline took effect. */
    private final BooleanSupplier cancelRequested;
    /** Adds warnings to the run's: the errors of the secondary outputs that only warn. */
    private final Consumer<List<String>> runWarnings;

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
               WriteContext writeContext, Supplier<ItemLayout> layout, Supplier<IOutAction> outAction,
               BooleanSupplier cancelRequested, Consumer<List<String>> runWarnings) {
        this.registry = registry;
        this.state = state;
        this.tasks = tasks;
        this.notifier = notifier;
        this.writeContext = writeContext;
        this.layout = layout;
        this.outAction = outAction;
        this.cancelRequested = cancelRequested;
        this.runWarnings = runWarnings;
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
            ItemLayout items = layout.get();
            List<PipelineStep<?>> steps = items.steps();
            StepOutcome outcome = StepOutcome.CONTINUE;
            for (int i = fromStep; i < toStep; i++) {
                ItemLayout.BranchRange branch = items.branchAt(i);
                if (branch != null) {
                    runBranch(exec, branch, steps, ticket); // whatever it wrote, the item goes on
                    i = branch.outIndex(); // on with the step after the branch
                    continue;
                }
                PipelineStep<?> step = steps.get(i);
                Set<String> footprint = acquire(exec, step, i, exec.getWorkItem(), ticket);
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
                exec.setSkippedByOutput(outcome.skipReason(), outcome.target());
            } else if (!outcome.continueItem()) {
                exec.setFiltered();
            } else if (toStep == steps.size() && finalPhase) {
                exec.setWritten(outcome.target());
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
            ItemStatus status = exec.getStatus();
            if (finalPhase || status == ItemStatus.DONE || status == ItemStatus.SKIPPED || status == ItemStatus.ERROR) {
                afterItem(exec.finish());
            }
            notifier.changed();
        }
    }

    /**
     * {@link IAction#afterItem} for every step that began for these finished items, the last step first. A failure
     * is logged: the item is already finished, and the other steps still clean up.
     */
    private static void afterItem(List<WorkItemExecution> finished) {
        for (WorkItemExecution exec : finished) {
            afterItem(exec.getPipelineSteps(), List.copyOf(exec.handedItems().entrySet()).reversed());
        }
    }

    /** {@link IAction#afterItem} of each step for the item it was handed, in this order, every one of them called. */
    private static void afterItem(List<PipelineStep<?>> steps, List<Map.Entry<Integer, WorkItem>> handed) {
        for (Map.Entry<Integer, WorkItem> began : handed) {
            IAction action = steps.get(began.getKey()).getAction();
            try {
                action.afterItem(began.getValue());
            } catch (Throwable t) {
                LOG.warn(t, "item.after-item.failed", began.getValue().getNameDisplay(), String.valueOf(t));
            }
        }
    }

    /**
     * The resources of the step {@code index} for this item, waited for; the item is then RUNNING that step.
     * <p>
     * The action tells its resources ({@code requiredResources}) with the progress channel of the item bound and the
     * item RUNNING the step meanwhile: an action doing some work there (e.g. finding, or downloading, the program its
     * resource depends on) shows its progress as in the step itself. Its failure is redacted like one of the step.
     *
     * @throws InterruptedException interrupted while waiting, or the pipeline is cancelled
     */
    private Set<String> acquire(WorkItemExecution exec, PipelineStep<?> step, int index, WorkItem item, long ticket)
            throws InterruptedException {
        exec.setRunning(index); // the reports of this step are taken from now on
        Set<String> footprint;
        try {
            footprint = ItemProgress.callWith(progress(exec, index),
                    () -> FootprintResolver.resolve(step.getAction(), item, step.getConfig(), index));
        } catch (RuntimeException e) {
            throw redacted(step, e); // a failure of the action, without its secret values as in the step itself
        }
        exec.setWaitingResources(index, footprint);
        notifier.changed();
        registry.acquireAll(footprint, ticket);
        if (cancelRequested.getAsBoolean()) {
            // cancel() lifts a pause before the tasks are shut down: the waiters it grants must not start their step
            registry.releaseAll(footprint);
            throw new InterruptedException("cancelled");
        }
        exec.setRunning(index);
        notifier.changed();
        return footprint;
    }

    /**
     * One secondary output for this item (spec secondary-outputs §3): its own copy of the analysed item, without
     * companions, marked source kept, without the item's own out pattern (the main output's); each item a process
     * step produces goes on alone to the out step, in sequence. Then {@link IAction#afterItem} of its steps for each
     * item they were handed, the last first, whatever happened. An error of an output that fails the item is thrown,
     * the name of the output in front; one that only warns becomes a warning of the run. A cancel is never converted.
     */
    private void runBranch(WorkItemExecution exec, ItemLayout.BranchRange branch, List<PipelineStep<?>> steps,
                           long ticket) throws InterruptedException {
        // each copy has its own metadata tables: the output never writes into the item of the main branch
        WorkItem copy = exec.getWorkItem().copyForDryRun().withoutCompanions().withSourceKept();
        copy.setOutPattern(null); // the item's own out pattern is the main output's (spec secondary-outputs §6)
        List<Map.Entry<Integer, WorkItem>> handed = new ArrayList<>();
        BranchTally tally = new BranchTally();
        try {
            runBranchFrom(exec, steps, copy, branch.from(), handed, tally, ticket);
            exec.setBranchResult(tally.result(branch.name()));
        } catch (PhaseStopped | RejectedExecutionException | VirtualMachineError e) {
            throw e; // control, or the JVM failing: never an error of the output
        } catch (RuntimeException | Error t) {
            if (cancelRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw t; // an interruption of the item, told apart by run()
            }
            String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            if (branch.onError() == SecondaryOutputConfig.OnError.FAIL) {
                exec.setBranchResult(tally.failed(branch.name(), WorkItemExecution.BranchOutcome.ERROR, message));
                throw CopybotException.ofResource(t, "secondary.item.failed", branch.name(), message);
            }
            exec.setBranchResult(tally.failed(branch.name(), WorkItemExecution.BranchOutcome.WARNING, message));
            runWarnings.accept(List.of(ResourcesEngine.getString("secondary.item.warning", branch.name(), message,
                    exec.getWorkItem().getNameDisplay())));
        } finally {
            afterItem(steps, handed.reversed());
        }
    }

    /** The step {@code index} of a secondary output for one item, then the next steps for each item it produced. */
    private void runBranchFrom(WorkItemExecution exec, List<PipelineStep<?>> steps, WorkItem item, int index,
                               List<Map.Entry<Integer, WorkItem>> handed, BranchTally tally, long ticket)
            throws InterruptedException {
        PipelineStep<?> step = steps.get(index);
        List<WorkItem> produced;
        Set<String> footprint = acquire(exec, step, index, item, ticket);
        try {
            handed.add(Map.entry(index, item));
            produced = aroundStep(exec, step, index, item, progress -> runBranchStep(step, item, progress, tally));
        } catch (Error e) {
            // without the secret values, like a runtime exception (aroundStep): it may become the item's error or a
            // warning of the run; the JVM failing is left as it is
            if (e instanceof VirtualMachineError) {
                throw e;
            }
            Throwable hidden = step.getRedactor().redact(e);
            if (hidden instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw hidden instanceof Error error ? error : e;
        } finally {
            registry.releaseAll(footprint);
        }
        for (WorkItem next : produced) {
            // a process may hand on a new item of the same source: no out step of a branch ever deletes it
            runBranchFrom(exec, steps, next.isSourceKept() ? next : next.withSourceKept(), index + 1, handed, tally,
                    ticket);
        }
    }

    /** @return the items the next step of the output gets: none after its out step, nor when a process filters */
    private List<WorkItem> runBranchStep(PipelineStep<?> step, WorkItem item, ItemProgress progress, BranchTally tally) {
        IAction action = step.getAction();
        if (action instanceof IProcessAction process) {
            List<WorkItem> produced = process.doProcess(item);
            return produced == null ? List.of() : produced;
        }
        if (action instanceof IOutAction out) {
            tally.add(out.write(item, writeContext.withProgress(progress)));
            return List.of();
        }
        throw new UnsupportedOperationException("Unsupported action type: " + action.getClass());
    }

    /** What the out step of a secondary output did with the items it got, for {@link WorkItemExecution#getBranchResults}. */
    private static final class BranchTally {
        private final List<Path> targets = new ArrayList<>();
        private int written;
        private int skipped;
        private String skipReason;

        void add(WriteResult result) {
            if (result != null && result.isSkipped()) {
                skipped++;
                if (skipReason == null) {
                    skipReason = result.reason();
                }
            } else {
                written++;
            }
            if (result != null && result.target() != null) {
                targets.add(result.target());
            }
        }

        /** Written when it wrote one item at least, skipped when it skipped every one, filtered when it got none. */
        WorkItemExecution.BranchResult result(String name) {
            if (written > 0) {
                return new WorkItemExecution.BranchResult(name, WorkItemExecution.BranchOutcome.WRITTEN, targets, null);
            }
            if (skipped > 0) {
                return new WorkItemExecution.BranchResult(name, WorkItemExecution.BranchOutcome.SKIPPED, targets,
                        skipReason);
            }
            return new WorkItemExecution.BranchResult(name, WorkItemExecution.BranchOutcome.FILTERED, targets, null);
        }

        WorkItemExecution.BranchResult failed(String name, WorkItemExecution.BranchOutcome outcome, String message) {
            return new WorkItemExecution.BranchResult(name, outcome, targets, message);
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
     * @param target       where the out step wrote the item, or the existing target that made it skip it; null when
     *                     it cannot tell (or for another step)
     */
    private record StepOutcome(boolean continueItem, String skipReason, Path target) {
        static final StepOutcome CONTINUE = new StepOutcome(true, null, null);
        /** Filtered out by a process step: the item ends DONE. */
        static final StepOutcome FILTERED = new StepOutcome(false, null, null);

        static StepOutcome skipped(String reason, Path target) {
            return new StepOutcome(false, reason, target);
        }

        static StepOutcome written(Path target) {
            return new StepOutcome(true, null, target);
        }
    }

    /**
     * One step of one item, with what the action gets around it: {@link IAction#beforeEach} and
     * {@link IAction#afterEach} (a failure of either fails the item, like a failure of the step), and the progress
     * channel of the item ({@link ItemProgress#current()}, {@link WriteContext#progress()}): its reports reach the
     * watcher through {@link WorkItemExecution#getWorkStatus()}.
     */
    private StepOutcome runStepOfItem(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep) {
        WorkItem item = exec.getWorkItem();
        exec.stepBegan(stepIndex, item);
        return aroundStep(exec, step, stepIndex, item, progress -> runStep(exec, step, stepIndex, toStep, progress));
    }

    /** The body of one step handed this item, with the lifecycle calls and the progress channel told above. */
    private <R> R aroundStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, WorkItem item,
                             Function<ItemProgress, R> body) {
        IAction action = step.getAction();
        ItemProgress progress = progress(exec, stepIndex);
        return ItemProgress.callWith(progress, () -> {
            try {
                action.beforeEach(item);
            } catch (RuntimeException e) {
                throw redacted(step, e);
            }
            R outcome;
            try {
                outcome = body.apply(progress);
            } catch (Throwable t) {
                try {
                    action.afterEach(item);
                } catch (Throwable after) {
                    t.addSuppressed(after); // the failure of the step is the one to report
                }
                if (t instanceof RuntimeException e) {
                    throw redacted(step, e);
                }
                throw t;
            }
            try {
                action.afterEach(item);
            } catch (RuntimeException e) {
                throw redacted(step, e);
            }
            return outcome;
        });
    }

    /** The progress channel of the item for the step: its reports reach the watcher. */
    private ItemProgress progress(WorkItemExecution exec, int stepIndex) {
        return status -> {
            exec.reportProgress(stepIndex, status);
            notifier.changed();
        };
    }

    /**
     * The failure of the step's action without the secret values it was configured with: the error of the item never
     * shows one. The engine's own control exceptions are left as they are, whatever their text: the callers tell them
     * apart by their type.
     */
    static RuntimeException redacted(PipelineStep<?> step, RuntimeException e) {
        if (e instanceof PhaseStopped || e instanceof RejectedExecutionException) {
            return e;
        }
        return step.getRedactor().redact(e);
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
                WorkItemExecution forked = new WorkItemExecution(produced.get(i), layout.get().steps());
                forked.setParent(exec);
                state.getWorkItems().add(forked);
                notifier.changed();
                exec.forkStarted();
                try {
                    submit(forked, stepIndex + 1, toStep);
                } catch (RejectedExecutionException e) {
                    afterItem(forked.finish()); // never runs: not waited for
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
                return StepOutcome.skipped(result.reason(), result.target()); // the steps after it, if any, do not run
            }
            return StepOutcome.written(result == null ? null : result.target());
        }
        throw new UnsupportedOperationException("Unsupported action type: " + action.getClass());
    }
}
