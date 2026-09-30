package com.copybot.engine.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineStateTest {

    @Test
    public void compareAndSetStatusOnlyReplacesTheExpectedStatus() {
        PipelineState state = new PipelineState(List.of());
        state.setStatus(PipelineStatus.RUNNING);

        assertTrue(state.compareAndSetStatus(PipelineStatus.RUNNING, PipelineStatus.PAUSED));
        assertEquals(PipelineStatus.PAUSED, state.getStatus());
        assertFalse(state.compareAndSetStatus(PipelineStatus.RUNNING, PipelineStatus.PAUSED));

        state.setStatus(PipelineStatus.SUCCESS);
        assertFalse(state.compareAndSetStatus(PipelineStatus.PAUSED, PipelineStatus.RUNNING),
                "a resume after the end must not revive the run");
        assertEquals(PipelineStatus.SUCCESS, state.getStatus());
    }

    @Test
    public void onlyTheFirstRecordedFailureIsKept() {
        PipelineState state = new PipelineState(List.of());
        IllegalStateException first = new IllegalStateException("first");

        assertTrue(state.recordFailureIfAbsent(first));
        assertFalse(state.recordFailureIfAbsent(new IllegalStateException("second")));

        assertSame(first, state.getFailure());
    }

    @Test
    public void concurrentFailuresRecordExactlyOne() throws Exception {
        PipelineState state = new PipelineState(List.of());
        AtomicInteger recorded = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            IllegalStateException failure = new IllegalStateException("listing " + i);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (state.recordFailureIfAbsent(failure)) {
                    recorded.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
            assertFalse(thread.isAlive());
        }

        assertEquals(1, recorded.get(), "exactly one listing failure wins");
        assertNotNull(state.getFailure());
    }
}
