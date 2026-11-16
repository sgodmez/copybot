package com.copybot.engine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Hands a live value to its watcher at most once every {@value #PERIOD_MILLIS} ms, from a background thread, plus
 * once when stopped: the watcher always sees the latest value, not every single change.
 *
 * @param <T> the value watched (read by the watcher, never copied)
 */
final class CoalescingNotifier<T> {

    private static final System.Logger LOGGER = System.getLogger(CoalescingNotifier.class.getName());

    /** Minimum delay between two notifications. */
    static final long PERIOD_MILLIS = 100;

    /** null: nothing to notify, every call is a no-op. */
    private final Consumer<T> watcher;
    private final T value;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** The watcher failed once: logged, the next failures are not (it is called ten times a second). */
    private final AtomicBoolean watcherFailureLogged = new AtomicBoolean(false);
    private final Object watcherLock = new Object();
    private volatile Thread notifier;

    CoalescingNotifier(Consumer<T> watcher, T value) {
        this.watcher = watcher;
        this.value = value;
    }

    /**
     * Marks the value as changed. This fires ~(2*steps+2) times per item from every virtual thread,
     * so it must stay O(1) and lock-free: pushing each transition straight to the watcher melts both
     * consumers (the JavaFX UI rebuilds its whole list per notification, the CLI prints one line).
     * The dedicated notifier thread coalesces the flag into at most one call per
     * {@value #PERIOD_MILLIS} ms.
     */
    void changed() {
        dirty.set(true);
    }

    void start() {
        if (watcher == null) {
            return;
        }
        notifier = Thread.ofVirtual().name("copybot-watcher-notifier").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (dirty.compareAndSet(true, false)) {
                        deliver();
                    }
                    Thread.sleep(PERIOD_MILLIS);
                }
            } catch (InterruptedException e) {
                // normal stop: the terminal notification is done by stop()
            }
        });
    }

    /** Stops the notifier and delivers the terminal value, which must never be missed. */
    void stop() {
        Thread current = notifier;
        notifier = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(PERIOD_MILLIS * 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        dirty.set(false);
        deliver();
    }

    private void deliver() {
        if (watcher == null) {
            return;
        }
        synchronized (watcherLock) { // serializes the periodic and the terminal notification
            try {
                watcher.accept(value);
            } catch (RuntimeException e) {
                // An observer must never break the pipeline: a throwing watcher must not leak
                // resource permits or a pending-task registration (see TaskGroup.submit),
                // so we keep going. Logged once: it is called up to ten times a second.
                if (watcherFailureLogged.compareAndSet(false, true)) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "the pipeline watcher failed (its next failures are not logged)", e);
                }
            }
        }
    }
}
