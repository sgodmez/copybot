package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A pipeline running on a background thread of its {@link CopybotEngine}, returned by
 * {@link CopybotEngine#execute} and {@link CopybotEngine#run}. Every method is thread-safe.
 * Decide on the final status, not on {@code getState().getFailure()}: a CANCELLED run may carry a cause.
 */
public final class Execution {

    private final MainExecutor executor;
    private final CountDownLatch done = new CountDownLatch(1);

    Execution(MainExecutor executor) {
        this.executor = executor;
    }

    /** The live, mutating state: read it, do not retain it. */
    public PipelineState getState() {
        return executor.getState();
    }

    /** Waits for the end of the execution and returns its final status (SUCCESS, ERROR or CANCELLED). */
    public PipelineStatus await() throws InterruptedException {
        done.await();
        return getState().getStatus();
    }

    /** @return true when the execution ended within the delay (its final status is then in getState()) */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    /**
     * Clean stop: the current phase is interrupted, permits are released, the resume cursor is left
     * unchanged and the final status is CANCELLED. Lifts a pause first. Idempotent; no effect once done.
     * No effect either once the run has passed its point of no return (all its work done, publishing its
     * outcome): it then ends normally (SUCCESS or ERROR) and the resume cursor may be written.
     */
    public void cancel() {
        executor.cancel();
    }

    /**
     * How long, at most, the end-of-run actions of a cancelled run may still take: the sum of the time limits of the
     * ones still to run (an action without limit counted as 120 s) plus a margin; zero when there is none.
     */
    public Duration cancelledRunEndWait() {
        return executor.cancelledRunEndWait();
    }

    /** No new step starts (status PAUSED) until {@link #resume()}; running steps finish normally. */
    public void pause() {
        executor.pause();
    }

    public void resume() {
        executor.resume();
    }

    void markDone() {
        done.countDown();
    }
}
