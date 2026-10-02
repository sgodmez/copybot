package com.copybot.engine.resources;

import com.copybot.config.CopybotConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceRegistryTest {

    /** Starts a virtual thread that acquires the given resources; exposes latches to observe it. */
    static final class Acquirer {
        final CountDownLatch acquired = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch releaseSignal = new CountDownLatch(1);
        final Thread thread;

        Acquirer(ResourceRegistry registry, Set<String> resources) {
            thread = Thread.ofVirtual().start(() -> {
                try {
                    registry.acquireAll(resources);
                    acquired.countDown();
                    releaseSignal.await();
                    registry.releaseAll(resources);
                    released.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void release() throws InterruptedException {
            releaseSignal.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS));
        }
    }

    static ResourceRegistry registry(Map<String, Integer> capacities) {
        return new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null, capacities, null)));
    }

    static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met within 5s");
            Thread.sleep(5);
        }
    }

    static int waiting(ResourceRegistry reg, String name) {
        return reg.snapshot().stream()
                .filter(s -> s.name().equals(name))
                .mapToInt(ResourceSnapshot::waiting).sum();
    }

    static int used(ResourceRegistry reg, String name) {
        return reg.snapshot().stream()
                .filter(s -> s.name().equals(name))
                .mapToInt(ResourceSnapshot::used).sum();
    }

    @Test
    public void capacityIsEnforced() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer first = new Acquirer(reg, Set.of("r"));
        assertTrue(first.acquired.await(5, TimeUnit.SECONDS));

        Acquirer second = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);
        assertEquals(1, second.acquired.getCount(), "second acquirer must still be waiting");

        first.release();
        assertTrue(second.acquired.await(5, TimeUnit.SECONDS));
        second.release();
    }

    @Test
    public void allOrNothing_waiterHoldsNoPartialPermit() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer wantsBoth = new Acquirer(reg, Set.of("a", "b"));
        awaitTrue(() -> waiting(reg, "a") == 1);
        // while waiting for "a", the waiter must NOT hold "b"
        assertEquals(0, used(reg, "b"));

        holderOfA.release();
        assertTrue(wantsBoth.acquired.await(5, TimeUnit.SECONDS));
        assertEquals(1, used(reg, "a"));
        assertEquals(1, used(reg, "b"));
        wantsBoth.release();
    }

    @Test
    public void groupAliasCountsAsOneResource() throws Exception {
        ResourceRegistry reg = new ResourceRegistry(ResourceSettings.from(new CopybotConfig(null, null,
                Map.of("disk:D", 1), java.util.List.of(java.util.List.of("disk:D", "disk:E")))));
        Acquirer onD = new Acquirer(reg, Set.of("disk:D"));
        assertTrue(onD.acquired.await(5, TimeUnit.SECONDS));

        Acquirer onE = new Acquirer(reg, Set.of("disk:E")); // same physical disk => must wait
        awaitTrue(() -> waiting(reg, "disk:D") == 1);
        assertEquals(1, onE.acquired.getCount());

        onD.release();
        assertTrue(onE.acquired.await(5, TimeUnit.SECONDS));
        onE.release();
    }

    @Test
    public void firstFit_smallTaskBypassesBlockedBigTask() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer big = new Acquirer(reg, Set.of("a", "b")); // blocked on "a"
        awaitTrue(() -> waiting(reg, "a") == 1);

        Acquirer small = new Acquirer(reg, Set.of("b")); // arrived after big, but "b" is free
        assertTrue(small.acquired.await(5, TimeUnit.SECONDS), "small task must bypass the blocked big task");

        small.release();
        holderOfA.release();
        assertTrue(big.acquired.await(5, TimeUnit.SECONDS));
        big.release();
    }

    @Test
    public void antiStarvation_afterMaxBypassesNobodyOvertakes() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer big = new Acquirer(reg, Set.of("a", "b"));
        awaitTrue(() -> waiting(reg, "a") == 1);

        // bypass the big task MAX_BYPASS times with small tasks on "b"
        for (int i = 0; i < ResourceRegistry.MAX_BYPASS; i++) {
            Acquirer small = new Acquirer(reg, Set.of("b"));
            assertTrue(small.acquired.await(5, TimeUnit.SECONDS), "bypass #" + i + " should succeed");
            small.release();
        }

        // the big task is now starved: the next small task must NOT be granted
        Acquirer blockedSmall = new Acquirer(reg, Set.of("b"));
        awaitTrue(() -> waiting(reg, "b") >= 1);
        assertEquals(1, blockedSmall.acquired.getCount(), "starved waiter must not be bypassed anymore");

        holderOfA.release();
        assertTrue(big.acquired.await(5, TimeUnit.SECONDS), "starved big task acquires first");
        big.release();
        assertTrue(blockedSmall.acquired.await(5, TimeUnit.SECONDS));
        blockedSmall.release();
    }

    @Test
    public void antiStarvationOnlyReservesTheStarvedWaiterResources() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1));
        Acquirer holderOfA = new Acquirer(reg, Set.of("a"));
        assertTrue(holderOfA.acquired.await(5, TimeUnit.SECONDS));

        Acquirer starvedOnA = new Acquirer(reg, Set.of("a"));
        awaitTrue(() -> waiting(reg, "a") == 1);

        // starve it: MAX_BYPASS waiters arriving later are served before it
        for (int i = 0; i < ResourceRegistry.MAX_BYPASS; i++) {
            Acquirer bypasser = new Acquirer(reg, Set.of("b"));
            assertTrue(bypasser.acquired.await(5, TimeUnit.SECONDS), "bypass #" + i + " should succeed");
            bypasser.release();
        }

        // strict mode must apply to "a" only: "b" is idle and unrelated, so a waiter on {b}
        // is still granted immediately (independent resources keep running in parallel).
        Acquirer onIdleB = new Acquirer(reg, Set.of("b"));
        assertTrue(onIdleB.acquired.await(5, TimeUnit.SECONDS),
                "anti-starvation must not block waiters on resources the starved waiter does not need");
        assertEquals(1, starvedOnA.acquired.getCount(), "the starved waiter still waits for a");

        onIdleB.release();
        holderOfA.release();
        assertTrue(starvedOnA.acquired.await(5, TimeUnit.SECONDS));
        starvedOnA.release();
    }

    @Test
    public void registeredCapacityIsHonored() throws Exception {
        ResourceRegistry reg = registry(Map.of()); // "step:0" would default to capacity 1
        reg.registerCapacity("step:0", 3);
        reg.registerCapacity("step:0", 3); // idempotent

        Acquirer[] holders = new Acquirer[3];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new Acquirer(reg, Set.of("step:0"));
            assertTrue(holders[i].acquired.await(5, TimeUnit.SECONDS), "grant #" + i + " within the declared capacity");
        }
        assertEquals(3, used(reg, "step:0"));

        Acquirer beyondCapacity = new Acquirer(reg, Set.of("step:0"));
        awaitTrue(() -> waiting(reg, "step:0") == 1);
        assertEquals(1, beyondCapacity.acquired.getCount(), "the 4th acquirer must wait");

        holders[0].release();
        assertTrue(beyondCapacity.acquired.await(5, TimeUnit.SECONDS));
        beyondCapacity.release();
        holders[1].release();
        holders[2].release();
        assertEquals(0, used(reg, "step:0"));
    }

    @Test
    public void registerCapacityClampsBelowOne() throws Exception {
        ResourceRegistry reg = registry(Map.of());
        reg.registerCapacity("step:0", 0); // e.g. a misconfigured "maxConcurrency": 0

        assertEquals(1, reg.snapshot().stream().filter(s -> s.name().equals("step:0")).findFirst().orElseThrow().capacity());

        // an acquirer must still be granted (capacity 0 would hang it forever)
        Acquirer acquirer = new Acquirer(reg, Set.of("step:0"));
        assertTrue(acquirer.acquired.await(5, TimeUnit.SECONDS), "clamped capacity must still grant one permit");
        acquirer.release();
    }

    @Test
    public void registerCapacityLeavesAnInUseResourceUntouched() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer holder = new Acquirer(reg, Set.of("r"));
        assertTrue(holder.acquired.await(5, TimeUnit.SECONDS));

        reg.registerCapacity("r", 4); // permits are held: capacity must not be swapped underneath
        assertEquals(1, reg.snapshot().stream().filter(s -> s.name().equals("r")).findFirst().orElseThrow().capacity());
        assertEquals(1, used(reg, "r"));

        holder.release();
    }

    @Test
    @Timeout(10)
    public void emptyFootprintIsGrantedImmediately() throws Exception {
        // a step with no declared resources (e.g. a fake listing) must never block,
        // even while every known resource is saturated
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer holder = new Acquirer(reg, Set.of("r"));
        assertTrue(holder.acquired.await(5, TimeUnit.SECONDS));

        Acquirer empty = new Acquirer(reg, Set.of());
        assertTrue(empty.acquired.await(5, TimeUnit.SECONDS), "empty footprint must not wait");
        empty.release();
        holder.release();
    }

    @Test
    @Timeout(30)
    public void manyConcurrentWaitersDrainCompletely() throws Exception {
        // guards the indexed, event-driven implementation under load: 5000 waiters on one
        // capacity-4 resource must drain without a hang, a lost wakeup or a leaked permit
        ResourceRegistry reg = registry(Map.of("r", 4));
        int count = 5000;
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    reg.acquireAll(Set.of("r"));
                    maxObserved.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    concurrent.decrementAndGet();
                    reg.releaseAll(Set.of("r"));
                    done.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(done.await(25, TimeUnit.SECONDS), "all 5000 waiters must be served");
        assertTrue(maxObserved.get() <= 4, "capacity must never be exceeded, got " + maxObserved.get());
        assertEquals(0, used(reg, "r"));
        assertEquals(0, waiting(reg, "r"));
    }

    @Test
    public void interruptedWaiterLeavesNoTrace() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer holder = new Acquirer(reg, Set.of("r"));
        assertTrue(holder.acquired.await(5, TimeUnit.SECONDS));

        Acquirer waiter = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);

        waiter.thread.interrupt();
        awaitTrue(() -> waiting(reg, "r") == 0);
        assertEquals(1, waiter.acquired.getCount(), "interrupted waiter must not have acquired");

        holder.release();
        assertEquals(0, used(reg, "r"));
    }

    // ---- pause ----

    @Test
    @Timeout(30)
    public void pauseHoldsEveryNewGrantUntilResume() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 2));
        reg.pause();
        assertTrue(reg.isPaused());

        Acquirer waiter = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);
        assertFalse(waiter.acquired.await(200, TimeUnit.MILLISECONDS),
                "nothing is granted while paused, even with free capacity");

        reg.resume();
        assertFalse(reg.isPaused());
        assertTrue(waiter.acquired.await(5, TimeUnit.SECONDS), "resume grants what became grantable");
        waiter.release();
    }

    @Test
    @Timeout(30)
    public void holdersKeepTheirPermitsAndReleaseThemDuringThePause() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        Acquirer holder = new Acquirer(reg, Set.of("r"));
        assertTrue(holder.acquired.await(5, TimeUnit.SECONDS));
        reg.pause();
        Acquirer next = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);

        holder.release(); // a step already running ends and releases normally
        assertEquals(0, used(reg, "r"));
        assertFalse(next.acquired.await(200, TimeUnit.MILLISECONDS),
                "the released permit is not handed over while paused");

        reg.resume();
        assertTrue(next.acquired.await(5, TimeUnit.SECONDS));
        next.release();
    }

    @Test
    @Timeout(30)
    public void interruptingAWaiterDuringThePauseLeavesNothingBehind() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        reg.pause();
        Acquirer waiter = new Acquirer(reg, Set.of("r"));
        awaitTrue(() -> waiting(reg, "r") == 1);

        waiter.thread.interrupt();
        waiter.thread.join(5000);

        assertFalse(waiter.thread.isAlive());
        assertEquals(1, waiter.acquired.getCount(), "an interrupted waiter is never granted");
        assertEquals(0, waiting(reg, "r"));
        reg.resume();
        assertEquals(0, used(reg, "r"), "resume must not grant a permit to the interrupted waiter");
    }

    @Test
    @Timeout(30)
    public void anEmptyFootprintAlsoWaitsForTheResume() throws Exception {
        ResourceRegistry reg = registry(Map.of());
        reg.pause();
        Acquirer nothing = new Acquirer(reg, Set.of());
        assertFalse(nothing.acquired.await(200, TimeUnit.MILLISECONDS), "no step starts while paused");

        reg.resume();
        assertTrue(nothing.acquired.await(5, TimeUnit.SECONDS));
        nothing.release();
    }

    @Test
    @Timeout(30)
    public void awaitNotPausedBlocksUntilResumeAndIsInterruptible() throws Exception {
        ResourceRegistry reg = registry(Map.of());
        reg.pause();
        CountDownLatch passed = new CountDownLatch(1);
        Thread blocked = Thread.ofVirtual().start(() -> {
            try {
                reg.awaitNotPaused();
                passed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread cancelled = Thread.ofVirtual().start(() -> {
            try {
                reg.awaitNotPaused();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertFalse(passed.await(200, TimeUnit.MILLISECONDS), "awaitNotPaused must block while paused");

        cancelled.interrupt();
        cancelled.join(5000);
        assertTrue(interrupted.get(), "awaitNotPaused must be interruptible");

        reg.resume();
        assertTrue(passed.await(5, TimeUnit.SECONDS), "resume must wake awaitNotPaused");
        blocked.join(5000);
    }

    @Test
    @Timeout(30)
    public void oneResumeGrantsEveryWaiterThatBecameGrantable() throws Exception {
        ResourceRegistry reg = registry(Map.of("a", 1, "b", 1, "c", 1));
        reg.pause();
        Acquirer onA = new Acquirer(reg, Set.of("a"));
        Acquirer onB = new Acquirer(reg, Set.of("b"));
        Acquirer onC = new Acquirer(reg, Set.of("c"));
        awaitTrue(() -> waiting(reg, "a") + waiting(reg, "b") + waiting(reg, "c") == 3);

        reg.resume();

        assertTrue(onA.acquired.await(5, TimeUnit.SECONDS));
        assertTrue(onB.acquired.await(5, TimeUnit.SECONDS));
        assertTrue(onC.acquired.await(5, TimeUnit.SECONDS));
        onA.release();
        onB.release();
        onC.release();
    }

    @Test
    @Timeout(30)
    public void thePauseDoesNotCountAsWaitingTimeForTheAntiStarvation() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        ResourceRegistry reg = new ResourceRegistry(
                ResourceSettings.from(new CopybotConfig(null, null, Map.of("a", 1, "b", 1), null)), now::get);
        Acquirer holderOfB = new Acquirer(reg, Set.of("b"));
        assertTrue(holderOfB.acquired.await(5, TimeUnit.SECONDS));
        Acquirer big = new Acquirer(reg, Set.of("a", "b")); // cannot fit while b is held
        awaitTrue(() -> waiting(reg, "a") == 1);

        reg.pause();
        now.addAndGet(2 * ResourceRegistry.MAX_WAIT_MILLIS); // a long pause
        reg.resume();

        // the big waiter has not waited long while the registry was granting: it is not starved, so a
        // waiter on the free "a" may still bypass it (otherwise strict FIFO on "a" right after the resume)
        Acquirer small = new Acquirer(reg, Set.of("a"));
        assertTrue(small.acquired.await(5, TimeUnit.SECONDS), "the paused time must not starve the big waiter");

        small.release();
        holderOfB.release();
        assertTrue(big.acquired.await(5, TimeUnit.SECONDS));
        big.release();
    }

    @Test
    @Timeout(30)
    public void snapshotExposesThePause() throws Exception {
        ResourceRegistry reg = registry(Map.of("r", 1));
        reg.acquireAll(Set.of("r"));
        reg.releaseAll(Set.of("r"));
        assertTrue(reg.snapshot().stream().noneMatch(ResourceSnapshot::paused));

        reg.pause();
        assertTrue(reg.snapshot().stream().allMatch(ResourceSnapshot::paused));

        reg.resume();
        assertTrue(reg.snapshot().stream().noneMatch(ResourceSnapshot::paused));
    }
}
