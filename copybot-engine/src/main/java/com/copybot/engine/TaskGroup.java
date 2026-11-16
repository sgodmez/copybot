package com.copybot.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The tasks of a pipeline phase: one virtual thread each, and the wait until every one of them, including the ones
 * submitted by other tasks, is over.
 *
 * <p>Completion is tracked with a plain counter ({@code pendingTasks}) guarded by
 * {@code completionLock}, not a {@link java.util.concurrent.Phaser}: a Phaser's registered-parties
 * count is capped at 65534, and in two-phase mode every listed item registers before any of them
 * can deregister (they all park on the listing gate), so a single listing beyond that
 * size would overflow it. Large listings (100k+ files) are an explicit use case of this project.
 */
final class TaskGroup {

    /** Best-effort grace period given to in-flight tasks to unwind after a shutdown request. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private ExecutorService taskExecutor;

    private final Object completionLock = new Object();
    private long pendingTasks;

    /** At the start of a phase: a new executor, the one of the previous phase being shut down. */
    void open() {
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** True once the executor of the phase is shut down: a task it rejects then means the phase is being stopped. */
    boolean isShutdown() {
        return taskExecutor.isShutdown();
    }

    /**
     * Interrupts whatever is still running ({@code shutdownNow}, not {@code shutdown}: threads parked
     * in {@code acquireAll} would never wake up otherwise) and waits briefly for the tasks to unwind,
     * so their {@code finally} blocks release their permits and close their streams before the caller
     * (possibly the JVM) moves on. Idempotent and harmless once everything has completed.
     */
    void shutdown() {
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
    void submit(Runnable task) {
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

    /** Waits until no task is pending, the ones submitted meanwhile included. */
    void awaitCompletion() throws InterruptedException {
        synchronized (completionLock) {
            while (pendingTasks > 0) {
                completionLock.wait();
            }
        }
    }
}
