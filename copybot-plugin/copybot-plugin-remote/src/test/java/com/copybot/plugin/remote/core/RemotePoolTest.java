package com.copybot.plugin.remote.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class RemotePoolTest {

    private final InMemoryServer server = new InMemoryServer();

    private RemotePool pool(int max) {
        return new RemotePool(server::connect, max, Duration.ofSeconds(30), System::nanoTime, null);
    }

    @Test
    public void aReturnedConnectionIsReused() throws IOException {
        RemotePool pool = pool(2);
        RemoteFs first;
        try (RemotePool.Lease lease = pool.borrow()) {
            first = lease.fs();
        }
        try (RemotePool.Lease lease = pool.borrow()) {
            assertSame(first, lease.fs());
        }
        assertEquals(1, server.connects.get());
        assertEquals(1, pool.idleConnections());
    }

    @Test
    public void noMoreThanMaxConnectionsAreBorrowed() throws Exception {
        RemotePool pool = pool(2);
        RemotePool.Lease a = pool.borrow();
        RemotePool.Lease b = pool.borrow();
        CountDownLatch borrowed = new CountDownLatch(1);
        Thread third = Thread.ofVirtual().start(() -> {
            try (RemotePool.Lease c = pool.borrow()) {
                borrowed.countDown();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        assertFalse(borrowed.await(200, TimeUnit.MILLISECONDS), "a third connection while two are borrowed");
        a.close();
        assertTrue(borrowed.await(5, TimeUnit.SECONDS));
        third.join();
        b.close();
        assertEquals(2, server.connects.get(), "the third reused the returned one");
    }

    @Test
    public void anInterruptedWaitThrowsAndKeepsTheInterrupt() throws Exception {
        RemotePool pool = pool(1);
        RemotePool.Lease held = pool.borrow();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                pool.borrow().close();
            } catch (IOException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(100);
        waiting.interrupt();
        waiting.join();
        assertInstanceOf(InterruptedIOException.class, failure.get());
        assertTrue(interrupted.get());
        held.close();
        // the permit of the interrupted wait was not lost
        pool.borrow().close();
    }

    @Test
    public void anInvalidatedConnectionIsClosedNotReused() throws IOException {
        RemotePool pool = pool(1);
        try (RemotePool.Lease lease = pool.borrow()) {
            lease.invalidate();
        }
        assertEquals(1, server.closes.get());
        assertEquals(0, pool.idleConnections());
        try (RemotePool.Lease lease = pool.borrow()) {
            assertNotNull(lease.fs());
        }
        assertEquals(2, server.connects.get());
    }

    @Test
    public void anUnusableIdleConnectionIsReplaced() throws IOException {
        RemotePool pool = pool(1);
        try (RemotePool.Lease lease = pool.borrow()) {
            ((InMemoryServer.Connection) lease.fs()).usable = false;
        }
        try (RemotePool.Lease lease = pool.borrow()) {
            assertTrue(lease.fs().isUsable());
        }
        assertEquals(2, server.connects.get());
        assertEquals(1, server.closes.get());
    }

    @Test
    public void aFailedConnectionGivesBackItsPermit() throws IOException {
        RemotePool pool = pool(1);
        server.failConnect = true;
        assertThrows(IOException.class, pool::borrow);
        server.failConnect = false;
        pool.borrow().close();
        assertEquals(1, server.connects.get());
    }

    @Test
    public void closeAllClosesIdleConnectionsAndThoseBorrowedOnTheirReturn() throws IOException {
        RemotePool pool = pool(3);
        RemotePool.Lease a = pool.borrow();
        RemotePool.Lease b = pool.borrow();
        a.close();
        pool.closeAll();
        assertEquals(1, server.closes.get(), "the idle one");
        b.close();
        assertEquals(2, server.closes.get(), "borrowed before closeAll: closed on its return");
        assertEquals(0, server.open());
        // still usable: connects again
        try (RemotePool.Lease lease = pool.borrow()) {
            assertNotNull(lease.fs());
        }
        assertEquals(3, server.connects.get());
        pool.closeAll();
        assertEquals(0, server.open());
    }

    @Test
    public void aFailingCloseNeitherLosesThePermitNorStopsTheOthers() throws IOException {
        RemotePool pool = pool(2);
        RemotePool.Lease a = pool.borrow();
        RemotePool.Lease b = pool.borrow();
        a.close();
        b.close();
        server.failClose = true;
        pool.closeAll();
        assertEquals(2, server.closes.get(), "both closed");
        RemotePool.Lease c = pool.borrow();
        c.invalidate();
        c.close(); // its close fails
        RemotePool.Lease d = pool.borrow();
        RemotePool.Lease e = pool.borrow(); // two permits still there
        d.close();
        e.close();
    }

    @Test
    public void aLeaseClosedTwiceReturnsOnce() throws Exception {
        RemotePool pool = pool(1);
        RemotePool.Lease lease = pool.borrow();
        lease.close();
        lease.close();
        RemotePool.Lease next = pool.borrow();
        // a second permit would let a second connection in: the pool of 1 must make this one wait
        Thread other = Thread.ofVirtual().start(() -> {
            try {
                pool.borrow().close();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        assertFalse(other.join(Duration.ofMillis(200)), "a second connection in a pool of one");
        next.close();
        assertTrue(other.join(Duration.ofSeconds(5)));
    }

    @Test
    public void idleConnectionsAreClosedAfterTheIdleTime() throws IOException {
        AtomicLong now = new AtomicLong();
        RemotePool pool = new RemotePool(server::connect, 2, Duration.ofSeconds(30), now::get, null);
        RemotePool.Lease a = pool.borrow();
        RemotePool.Lease b = pool.borrow();
        a.close();
        now.addAndGet(Duration.ofSeconds(20).toNanos());
        b.close();
        now.addAndGet(Duration.ofSeconds(15).toNanos());
        pool.evictIdle();
        assertEquals(1, server.closes.get(), "idle for 35 s: closed; the other, for 15 s, kept");
        assertEquals(1, pool.idleConnections());
        now.addAndGet(Duration.ofSeconds(20).toNanos());
        pool.evictIdle();
        assertEquals(0, server.open());
    }
}
