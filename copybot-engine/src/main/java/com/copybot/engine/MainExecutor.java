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
    public MainExecutor(PipelineConfig pipelineConfig, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this.pipelineConfig = pipelineConfig;
        this.watcher = watcher;
        this.registry = registry;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    // visible for tests: runs with pre-resolved steps, bypassing PluginEngine
    MainExecutor(List<PipelineStep<IInAction>> inSteps, List<PipelineStep<?>> itemSteps,
                 boolean startProcessingWhileListing, Consumer<PipelineState> watcher, ResourceRegistry registry) {
        this.pipelineConfig = null;
        this.watcher = watcher;
        this.registry = registry;
        this.inSteps = inSteps;
        this.itemSteps = itemSteps;
        this.startProcessingWhileListing = startProcessingWhileListing;
        this.state = new PipelineState(List.of());
        this.state.setRegistry(registry);
    }

    @Override
    public void run() {
        state.setStatus(PipelineStatus.RUNNING);
        state.setListingInProgress(true);
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        startNotifier();
        notifyWatcher();

        try {
            // Step resolution is INSIDE the try: a missing plugin/action must be reported as a
            // failed run (status ERROR + watcher notified), not as an exception out of a state-less run.
            if (pipelineConfig != null) {
                inSteps = doResolveStep(pipelineConfig.inSteps(), IInAction.class);
                itemSteps = resolveOtherSteps(pipelineConfig);
                startProcessingWhileListing = Boolean.TRUE.equals(pipelineConfig.startProcessingWhileListing());
            }
            registerStepCapacities();
            listingGate = new CountDownLatch(inSteps.size());

            for (PipelineStep<IInAction> inStep : inSteps) {
                submitTask(() -> runListing(inStep));
            }
            awaitCompletion(); // waits for all listings AND all items (including forked ones)
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
            shutdownTasks();
            stopNotifier(); // includes the final, guaranteed notification
        }
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
        submitItem(exec, 0);
    }

    private void submitItem(WorkItemExecution exec, int fromStep) {
        submitTask(() -> runItem(exec, fromStep));
    }

    private void runItem(WorkItemExecution exec, int fromStep) {
        try {
            if (!startProcessingWhileListing) {
                listingGate.await();
            }
            for (int i = fromStep; i < itemSteps.size(); i++) {
                PipelineStep<?> step = itemSteps.get(i);
                Set<String> footprint = FootprintResolver.resolve(step.getAction(), exec.getWorkItem(), step.getConfig(), i);
                exec.setWaitingResources(i, footprint);
                notifyWatcher();
                registry.acquireAll(footprint);
                exec.setRunning(i);
                notifyWatcher();
                boolean continueItem;
                try {
                    continueItem = runStep(exec, step, i);
                } finally {
                    registry.releaseAll(footprint);
                }
                if (!continueItem) {
                    break;
                }
            }
            exec.setDone();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            exec.setError(t);
        } finally {
            notifyWatcher();
        }
    }

    /**
     * @return true to continue with the next step, false when the item stops here (filtered out)
     */
    private boolean runStep(WorkItemExecution exec, PipelineStep<?> step, int stepIndex) {
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
                state.getWorkItems().add(forked);
                notifyWatcher();
                submitItem(forked, stepIndex + 1);
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
