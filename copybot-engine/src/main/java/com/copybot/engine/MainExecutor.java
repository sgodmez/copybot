package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resources.FootprintResolver;
import com.copybot.engine.resources.ResourceRegistry;
import com.copybot.engine.resume.ResumeContext;
import com.copybot.engine.resume.ResumeMode;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeResolver;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs one pipeline: one virtual thread per listing and per work item,
 * concurrency bounded by the ResourceRegistry (see the design spec).
 *
 * <p>Completion is tracked with a plain counter ({@code pendingTasks}) guarded by
 * {@code completionLock}, not a {@link java.util.concurrent.Phaser}: a Phaser's registered-parties
 * count is capped at 65534, and in two-phase mode every listed item registers before any of them
 * can deregister (they all park on {@code listingGate.await()}), so a single listing beyond that
 * size would overflow it. Large listings (100k+ files) are an explicit use case of this project.
 */
public class MainExecutor implements Runnable {

    /** Minimum delay between two watcher notifications (coalescing, see {@link #notifyWatcher()}). */
    static final long WATCHER_PERIOD_MILLIS = 100;

    /** Best-effort grace period given to in-flight tasks to unwind after a shutdown request. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private final PipelineConfig pipelineConfig;
    private final Consumer<PipelineState> watcher;
    private final ResourceRegistry registry;
    private final PipelineState state;

    private List<PipelineStep<IInAction>> inSteps;
    private List<PipelineStep<?>> itemSteps;
    private boolean startProcessingWhileListing;

    /** null: single-phase run without barrier nor resume (historical behaviour of run()). */
    private final ResumeContext resume;

    /** Number of item steps before the preparation barrier (the analyse steps). */
    private int barrierIndex;

    /** Exclusive end of the steps run by the items the listings emit: the barrier while preparing, all steps otherwise. */
    private volatile int phaseEnd;

    private ResumeResolver resolver;
    private ResumeProposal proposal;
    private List<WorkItemExecution> orderedItems = List.of();

    private ExecutorService taskExecutor;
    private CountDownLatch listingGate;
    private final AtomicBoolean listingFailed = new AtomicBoolean(false);

    private final Object completionLock = new Object();
    private long pendingTasks;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object watcherLock = new Object();
    private volatile Thread notifier;

    /**
     * @param watcher optional progress observer. It is invoked from a <em>background thread</em>
     *                (never from the caller's thread), at most once every
     *                {@value #WATCHER_PERIOD_MILLIS} ms (notifications are coalesced: the observer
     *                always sees the latest state, not every single transition), plus one final
     *                guaranteed notification once the run has terminated. Exceptions it throws are
     *                swallowed. The {@link PipelineState} handed over is the live, mutating state:
     *                read it, do not retain it.
     */
    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry,
                        ResumeContext resume) {
        this.pipelineConfig = pipelineConfig;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    // visible for tests: runs with pre-resolved steps, bypassing PluginEngine, without barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this(inSteps, itemSteps, itemSteps.size(), startProcessingWhileListing, watcher, registry, null);
    }

    // visible for tests: pre-resolved steps, the first barrierIndex item steps run before the barrier
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps, int barrierIndex,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry,
                 ResumeContext resume) {
        this.pipelineConfig = null;
        this.watcher = watcher;
        this.registry = registry;
        this.resume = resume;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.barrierIndex = barrierIndex;
        this.startProcessingWhileListing = startProcessingWhileListing;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    /**
     * Without resume: one phase, every step, as before. With resume: {@link #prepare()} then, when the
     * preparation succeeded, {@link #execute(ResumePoint)} with the proposed resume point.
     */
    @Override
    public void run() {
        if (resume == null) {
            runSinglePhase();
            return;
        }
        prepare();
        if (state.getStatus() == PipelineStatus.PREPARED) {
            execute(null);
        }
    }

    private void runSinglePhase() {
        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        startPhase();
        try {
            // Step resolution is INSIDE the try: a missing plugin/action must be reported as a
            // failed run (status ERROR + watcher notified), not as an exception out of a state-less run.
            resolveStepsIfNeeded();
            phaseEnd = itemSteps.size();
            runListings();
            state.setStatus(resolveFinalStatus());
        } catch (InterruptedException e) {
            // Cancellation: unblock every item still parked in acquireAll/IO and let it release its
            // permits, THEN restore the interrupt flag (awaitTermination would return immediately
            // with the flag set, leaving writers to be killed mid-stream by JVM exit).
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            state.setStatus(PipelineStatus.ERROR);
            throw e;
        } finally {
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /**
     * Lists and analyses every item, then resolves the resume point: items end PENDING (selected),
     * SKIPPED (with a reason) or ERROR, and the pipeline PREPARED. Nothing after the analyses runs.
     * A listing failure leaves the pipeline in ERROR: a partial listing would give a wrong resume point.
     *
     * @throws RuntimeException when the resume point cannot be resolved (e.g. invalid state file),
     *                          after the status has been set to ERROR
     */
    public void prepare() {
        if (resume == null) {
            throw new IllegalStateException("prepare() requires a resume context");
        }
        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        startPhase();
        try {
            resolveStepsIfNeeded();
            phaseEnd = barrierIndex;
            runListings();
            if (listingFailed.get()) {
                state.setStatus(PipelineStatus.ERROR);
                return;
            }
            orderedItems = ResumeResolver.order(state.getWorkItems());
            resolver = new ResumeResolver(resume.mode(), resume.store(), findOutAction());
            proposal = resolver.propose(orderedItems);
            state.setResumeProposal(proposal);
            resolver.apply(proposal.point(), proposal.source(), orderedItems);
            state.setStatus(PipelineStatus.PREPARED);
        } catch (InterruptedException e) {
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            state.setStatus(PipelineStatus.ERROR);
            state.setFailure(e);
            throw e;
        } finally {
            state.setListingInProgress(false);
            endPhase();
        }
    }

    /** Applies a manual resume point to the item statuses (also used by the dry-run preview). */
    void applyOverride(ResumePoint override) {
        if (override != null) {
            resolver.apply(override, ResumeSource.MANUAL, orderedItems);
        }
    }

    /**
     * Runs the steps after the barrier for the selected items, then advances the resume cursor
     * (only when the run completed normally and the mode is not NONE).
     *
     * @param override resume point chosen by the user, null for the proposed one
     */
    public void execute(ResumePoint override) {
        if (state.getStatus() != PipelineStatus.PREPARED) {
            throw new IllegalStateException("Pipeline is not prepared: " + state.getStatus());
        }
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        applyOverride(override);
        state.setStatus(PipelineStatus.RUNNING);
        startPhase();
        try {
            for (WorkItemExecution exec : orderedItems) {
                if (exec.getStatus() == ItemStatus.PENDING) {
                    submitItem(exec, barrierIndex, itemSteps.size());
                }
            }
            awaitCompletion();
            // The final status is published once, after the cursor save: a cursor write failure
            // must never be seen by watchers as SUCCESS followed by ERROR.
            PipelineStatus finalStatus = resolveFinalStatus();
            if (!saveCursor(point, source)) {
                finalStatus = PipelineStatus.ERROR;
            }
            state.setStatus(finalStatus);
        } catch (InterruptedException e) {
            state.setStatus(PipelineStatus.ERROR);
            shutdownTasks();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            state.setStatus(PipelineStatus.ERROR);
            state.setFailure(e);
            throw e;
        } finally {
            endPhase();
        }
    }

    /** @return false when the cursor could not be written */
    private boolean saveCursor(ResumePoint point, ResumeSource source) {
        if (resume.mode() == ResumeMode.NONE) {
            return true;
        }
        try {
            resolver.nextCursor(orderedItems, point, source).ifPresent(resume.store()::writeCursor);
            return true;
        } catch (RuntimeException e) {
            // the files are copied, but the next resume would not know it: this run is not a success
            state.setFailure(e);
            return false;
        }
    }

    private void resolveStepsIfNeeded() {
        if (pipelineConfig != null && inSteps == null) {
            inSteps = doResolveStep(pipelineConfig.inSteps(), IInAction.class);
            itemSteps = resolveOtherSteps(pipelineConfig);
            barrierIndex = pipelineConfig.analyseSteps() == null ? 0 : pipelineConfig.analyseSteps().size();
            startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
        }
        registerStepCapacities();
    }

    /** Submits every listing and waits for them AND every item they emitted (including forked ones). */
    private void runListings() throws InterruptedException {
        listingGate = new CountDownLatch(inSteps.size());
        for (PipelineStep<IInAction> inStep : inSteps) {
            submitTask(() -> runListing(inStep));
        }
        awaitCompletion();
    }

    private void startPhase() {
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startNotifier();
        notifyWatcher();
    }

    private void endPhase() {
        shutdownTasks();
        stopNotifier(); // includes the final, guaranteed notification
    }

    private IOutAction findOutAction() {
        if (!itemSteps.isEmpty() && itemSteps.getLast().getAction() instanceof IOutAction out) {
            return out;
        }
        return null;
    }

    ResumeProposal getProposal() {
        return proposal;
    }

    List<WorkItemExecution> getOrderedItems() {
        return orderedItems;
    }

    /**
     * Publishes the capacity of the implicit {@code step:<n>} resource that backs
     * {@code maxConcurrency} (design spec §4.3). Without this the name would be unknown to
     * {@code ResourceSettings} and fall back to the default capacity of 1, turning
     * {@code "maxConcurrency": 8} into a full serialization of the step.
     */
    private void registerStepCapacities() {
        for (int i = 0; i < itemSteps.size(); i++) {
            PipelineStepConfig config = itemSteps.get(i).getConfig();
            if (config != null && config.maxConcurrency() != null) {
                registry.registerCapacity("step:" + i, config.maxConcurrency());
            }
        }
    }

    /** A run is a success only if the listings AND every item succeeded. */
    private PipelineStatus resolveFinalStatus() {
        boolean anyItemFailed = state.getWorkItems().stream()
                .anyMatch(item -> item.getStatus() == ItemStatus.ERROR);
        return listingFailed.get() || anyItemFailed ? PipelineStatus.ERROR : PipelineStatus.SUCCESS;
    }

    /**
     * Interrupts whatever is still running ({@code shutdownNow}, not {@code shutdown}: threads parked
     * in {@code acquireAll} would never wake up otherwise) and waits briefly for the tasks to unwind,
     * so their {@code finally} blocks release their permits and close their streams before the caller
     * (possibly the JVM) moves on. Idempotent and harmless once everything has completed.
     */
    private void shutdownTasks() {
        if (taskExecutor == null) {
            return;
        }
        taskExecutor.shutdownNow();
        if (taskExecutor.isTerminated()) {
            return;
        }
        try {
            taskExecutor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Registers one pending task, then submits it to the executor. Registration happens
     * BEFORE submission (same discipline for every caller), so the pending count never drops
     * to zero prematurely; if the submission itself throws (e.g. RejectedExecutionException),
     * the registration is rolled back so {@link #awaitCompletion()} never hangs on a task that
     * was never actually queued.
     */
    private void submitTask(Runnable task) {
        synchronized (completionLock) {
            pendingTasks++;
        }
        try {
            taskExecutor.submit(() -> {
                try {
                    task.run();
                } finally {
                    completeTask();
                }
            });
        } catch (RuntimeException e) {
            completeTask();
            throw e;
        }
    }

    private void completeTask() {
        synchronized (completionLock) {
            pendingTasks--;
            if (pendingTasks == 0) {
                completionLock.notifyAll();
            }
        }
    }

    private void awaitCompletion() throws InterruptedException {
        synchronized (completionLock) {
            while (pendingTasks > 0) {
                completionLock.wait();
            }
        }
    }

    private void runListing(PipelineStep<IInAction> inStep) {
        try {
            Set<String> footprint = FootprintResolver.forListing(inStep.getAction(), inStep.getConfig());
            registry.acquireAll(footprint);
            try {
                inStep.getAction().listFiles(this::emitItem);
            } finally {
                registry.releaseAll(footprint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // A failing listing must not silently look like success: the whole run is marked ERROR.
            listingFailed.set(true);
            if (state.getFailure() == null) {
                state.setFailure(t);
            }
        } finally {
            listingGate.countDown();
            if (listingGate.getCount() == 0) {
                state.setListingInProgress(false);
                notifyWatcher();
            }
        }
    }

    private void emitItem(WorkItem workItem) {
        WorkItemExecution exec = new WorkItemExecution(workItem, itemSteps);
        state.getWorkItems().add(exec);
        notifyWatcher();
        submitItem(exec, 0, phaseEnd);
    }

    private void submitItem(WorkItemExecution exec, int fromStep, int toStep) {
        submitTask(() -> runItem(exec, fromStep, toStep));
    }

    /** Runs the steps [fromStep, toStep); an item stopped at the barrier goes back to PENDING. */
    private void runItem(WorkItemExecution exec, int fromStep, int toStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            boolean filtered = false;
            for (int i = fromStep; i < toStep; i++) {
                PipelineStep<?> step = itemSteps.get(i);
                Set<String> footprint = FootprintResolver.resolve(step.getAction(), exec.getWorkItem(), step.getConfig(), i);
                exec.setWaitingResources(i, footprint);
                notifyWatcher();
                registry.acquireAll(footprint);
                exec.setRunning(i);
                notifyWatcher();
                boolean continueItem;
                try {
                    continueItem = runStep(exec, step, i, toStep);
                } finally {
                    registry.releaseAll(footprint);
                }
                if (!continueItem) {
                    filtered = true;
                    break;
                }
            }
            if (filtered || toStep == itemSteps.size()) {
                exec.setDone();
            } else {
                exec.setReady();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            exec.setError(t);
            exec.propagateFailureToAncestors(); // a failed fork holds the resume cursor back before its parent
        } finally {
            notifyWatcher();
        }
    }

    /**
     * @param toStep exclusive end of the current phase: a forked item stops at the same step as its parent
     * @return true to continue with the next step, false when the item stops here (filtered out)
     */
    private boolean runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex, int toStep) {
        IAction action = step.getAction();
        WorkItem item = exec.getWorkItem();
        if (action instanceof IAnalyzeAction analyze) {
            analyze.doAnalyze(item);
            return true;
        }
        if (action instanceof IProcessAction process) {
            List<WorkItem> produced = process.doProcess(item);
            if (produced == null || produced.isEmpty()) {
                return false; // item filtered out
            }
            exec.replaceWorkItem(produced.get(0));
            for (int i = 1; i < produced.size(); i++) {
                WorkItemExecution forked = new WorkItemExecution(produced.get(i), itemSteps);
                forked.setParent(exec);
                state.getWorkItems().add(forked);
                notifyWatcher();
                submitItem(forked, stepIndex + 1, toStep);
            }
            return true;
        }
        if (action instanceof IOutAction out) {
            out.writeItem(item);
            return true;
        }
        throw new UnsupportedOperationException("Unsupported action type: " + action.getClass());
    }

    private static List<PipelineStep<?>> resolveOtherSteps(PipelineConfig pipelineConfig) {
        List<PipelineStep<?>> steps = new ArrayList<>();
        steps.addAll(doResolveStep(pipelineConfig.analyseSteps(), IAnalyzeAction.class));
        steps.addAll(doResolveStep(pipelineConfig.actionSteps(), IProcessAction.class));
        if (pipelineConfig.outStep() != null) {
            steps.add(PluginEngine.resolve(pipelineConfig.outStep(), IOutAction.class));
        }
        return Collections.unmodifiableList(steps);
    }

    private static <A extends IAction> List<PipelineStep<A>> doResolveStep(List<PipelineStepConfig> stepConfigs, Class<A> actionClass) {
        if (stepConfigs == null || stepConfigs.isEmpty()) {
            return List.of();
        }
        List<PipelineStep<A>> steps = new ArrayList<>(stepConfigs.size());
        for (PipelineStepConfig stepConfig : stepConfigs) {
            steps.add(PluginEngine.resolve(stepConfig, actionClass));
        }
        return Collections.unmodifiableList(steps);
    }

    /**
     * Marks the state as changed. This fires ~(2*steps+2) times per item from every virtual thread,
     * so it must stay O(1) and lock-free: pushing each transition straight to the watcher melts both
     * consumers (the JavaFX UI rebuilds its whole list per notification, the CLI prints one line).
     * The dedicated notifier thread coalesces the flag into at most one call per
     * {@value #WATCHER_PERIOD_MILLIS} ms.
     */
    private void notifyWatcher() {
        dirty.set(true);
    }

    private void startNotifier() {
        if (watcher == null) {
            return;
        }
        notifier = Thread.ofVirtual().name("copybot-watcher-notifier").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (dirty.compareAndSet(true, false)) {
                        deliverWatcher();
                    }
                    Thread.sleep(WATCHER_PERIOD_MILLIS);
                }
            } catch (InterruptedException e) {
                // normal stop: the terminal notification is done by stopNotifier()
            }
        });
    }

    /** Stops the notifier and delivers the terminal state, which must never be missed. */
    private void stopNotifier() {
        Thread current = notifier;
        notifier = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(WATCHER_PERIOD_MILLIS * 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        dirty.set(false);
        deliverWatcher();
    }

    private void deliverWatcher() {
        if (watcher == null) {
            return;
        }
        synchronized (watcherLock) { // serializes the periodic and the terminal notification
            try {
                watcher.accept(state);
            } catch (RuntimeException e) {
                // An observer must never break the pipeline: a throwing watcher must not leak
                // resource permits or a pending-task registration (see submitTask/completeTask),
                // so we swallow it here and keep going.
            }
        }
    }

    PipelineState getState() {
        return state;
    }
}
