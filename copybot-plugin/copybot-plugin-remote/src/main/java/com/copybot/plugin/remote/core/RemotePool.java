package com.copybot.plugin.remote.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The connections of one step to its server (spec remote §3): at most {@code maxConnections} at a time, borrowed by
 * the items and given back, a connection that failed closed instead of reused. {@link #closeAll} (the step's
 * {@code afterAll}) closes them; the pool stays usable, connecting again when asked.
 * <p>
 * The content of a listed item is read after the listing's {@code afterAll} (in the execution phase, which does not
 * begin the input step again): a connection given back outside a phase would stay open, so an idle connection is also
 * closed after {@code idleTime}.
 */
public final class RemotePool {

    /** Opens a connection. */
    @FunctionalInterface
    public interface Connector {
        RemoteFs connect() throws IOException;
    }

    /** The connections of a step when its configuration does not tell (spec remote §3). */
    public static final int DEFAULT_MAX_CONNECTIONS = 4;

    /** The default idle time before a connection is closed. */
    public static final Duration IDLE_TIME = Duration.ofSeconds(30);

    private static final System.Logger LOGGER = System.getLogger(RemotePool.class.getName());

    /** Closes the idle connections of every pool once their idle time is over. */
    private static final ScheduledExecutorService EVICTION = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("copybot-remote-idle").factory());

    private record Idle(RemoteFs fs, long since) {
    }

    private final Connector connector;
    private final Semaphore permits;
    private final long idleNanos;
    private final LongSupplier clock;
    private final ScheduledExecutorService eviction;

    // guarded by this
    private final Deque<Idle> idle = new ArrayDeque<>();
    private int generation;
    private boolean evictionScheduled;

    /** A pool closing its idle connections after {@link #IDLE_TIME}. */
    public RemotePool(Connector connector, int maxConnections) {
        this(connector, maxConnections, IDLE_TIME, System::nanoTime, EVICTION);
    }

    /**
     * @param clock    the time in nanoseconds ({@link System#nanoTime})
     * @param eviction runs {@link #evictIdle} once the idle time is over; null: only when called (tests)
     */
    RemotePool(Connector connector, int maxConnections, Duration idleTime, LongSupplier clock,
               ScheduledExecutorService eviction) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections " + maxConnections);
        }
        this.connector = connector;
        this.permits = new Semaphore(maxConnections, true);
        this.idleNanos = idleTime.toNanos();
        this.clock = clock;
        this.eviction = eviction;
    }

    /**
     * A connection for the calling thread, to give back by {@link Lease#close()}: an idle one still usable, or a new
     * one; waits while {@code maxConnections} are borrowed.
     *
     * @throws InterruptedIOException interrupted while waiting (a cancel), the interrupt kept
     * @throws IOException            the connection failed
     */
    public Lease borrow() throws IOException {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for a connection");
        }
        try {
            int borrowedGeneration;
            synchronized (this) {
                borrowedGeneration = generation;
            }
            // taken out of the idle ones under the lock, checked outside it (the check may be a call to the server)
            while (true) {
                RemoteFs candidate;
                synchronized (this) {
                    Idle next = idle.pollFirst();
                    candidate = next == null ? null : next.fs();
                }
                if (candidate == null) {
                    break;
                }
                if (usable(candidate)) {
                    return new Lease(candidate, borrowedGeneration);
                }
                closeQuietly(candidate);
            }
            return new Lease(connector.connect(), borrowedGeneration);
        } catch (IOException | RuntimeException | Error e) {
            permits.release();
            throw e;
        }
    }

    /**
     * Runs {@code operation} on a borrowed connection, given back after it; one that failed with an
     * {@link IOException} (or an {@link UncheckedIOException}) is closed.
     */
    public <T> T call(Operation<T> operation) throws IOException {
        try (Lease lease = borrow()) {
            try {
                return operation.run(lease.fs());
            } catch (IOException | UncheckedIOException e) {
                lease.invalidate(); // its state is unknown; a refusal of the caller (a conflict...) keeps it
                throw e;
            }
        }
    }

    /** An operation on a connection. */
    @FunctionalInterface
    public interface Operation<T> {
        T run(RemoteFs fs) throws IOException;
    }

    /**
     * Closes the idle connections now, and the borrowed ones when they are given back. The pool stays usable: the next
     * {@link #borrow} connects again.
     */
    public void closeAll() {
        List<Idle> closing;
        synchronized (this) {
            generation++;
            closing = new ArrayList<>(idle);
            idle.clear();
        }
        closing.forEach(i -> closeQuietly(i.fs()));
    }

    private static boolean usable(RemoteFs fs) {
        try {
            return fs.isUsable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Closes a connection; a failure (against the contract of {@link RemoteFs#close}) never stops the others. */
    private static void closeQuietly(RemoteFs fs) {
        try {
            fs.close();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.DEBUG, "closing a remote connection failed", e);
        }
    }

    /** Closes the connections idle for longer than the idle time. */
    void evictIdle() {
        List<Idle> closing = new ArrayList<>();
        long now = clock.getAsLong();
        synchronized (this) {
            evictionScheduled = false;
            for (Iterator<Idle> it = idle.iterator(); it.hasNext(); ) {
                Idle i = it.next();
                if (now - i.since() >= idleNanos) {
                    closing.add(i);
                    it.remove();
                }
            }
            scheduleEviction();
        }
        closing.forEach(i -> closeQuietly(i.fs()));
    }

    /** Schedules the next {@link #evictIdle} when connections are idle and none is scheduled; holding this. */
    private void scheduleEviction() {
        if (eviction == null || evictionScheduled || idle.isEmpty()) {
            return;
        }
        long oldest = idle.stream().mapToLong(Idle::since).min().orElseThrow();
        long delay = Math.max(0, oldest + idleNanos - clock.getAsLong());
        evictionScheduled = true;
        eviction.schedule(this::evictIdle, delay, TimeUnit.NANOSECONDS);
    }

    /** Visible for tests. */
    synchronized int idleConnections() {
        return idle.size();
    }

    /** A borrowed connection: given back by {@link #close()}, once. */
    public final class Lease implements AutoCloseable {
        private final RemoteFs fs;
        private final int borrowedGeneration;
        private volatile boolean invalid;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(RemoteFs fs, int borrowedGeneration) {
            this.fs = fs;
            this.borrowedGeneration = borrowedGeneration;
        }

        public RemoteFs fs() {
            return fs;
        }

        /** The connection failed (its state is unknown): closed when given back, never reused. */
        public void invalidate() {
            invalid = true;
        }

        /** Gives the connection back: kept idle for the next borrower, or closed. */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                boolean kept;
                synchronized (RemotePool.this) {
                    kept = !invalid && borrowedGeneration == generation;
                    if (kept) {
                        idle.addFirst(new Idle(fs, clock.getAsLong()));
                        scheduleEviction();
                    }
                }
                if (!kept) {
                    closeQuietly(fs);
                }
            } finally {
                permits.release();
            }
        }
    }
}
