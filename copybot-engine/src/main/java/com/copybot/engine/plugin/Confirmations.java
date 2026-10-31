package com.copybot.engine.plugin;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine side of {@link com.copybot.plugin.api.UserConfirmation}: the handler installed by the app (one per
 * JVM, like the plugins), the questions put one at a time, and the operations whose questions are answered no
 * without asking (the sample of the editor). Kept out of the plugin API so that no plugin can answer for the user.
 */
public final class Confirmations {

    private static final System.Logger LOGGER = System.getLogger(Confirmations.class.getName());

    /**
     * Bound while an operation answers no to every question without asking ({@link #declineDuring}): set once a
     * question was declined there.
     */
    private static final ScopedValue<AtomicBoolean> DECLINED = ScopedValue.newInstance();

    /** One question at a time, in the order they come. */
    private static final ReentrantLock ONE_AT_A_TIME = new ReentrantLock(true);

    private static volatile ConfirmationHandler handler;

    private Confirmations() {
    }

    /**
     * @param newHandler who answers from now on; null: no one (every question is answered no)
     * @return the handler it replaces, null for none
     */
    public static synchronized ConfirmationHandler setHandler(ConfirmationHandler newHandler) {
        ConfirmationHandler previous = handler;
        handler = newHandler;
        return previous;
    }

    /**
     * Runs the operation in this thread answering no to every question it asks, without asking the user; the other
     * threads (another run) are not affected, nor the threads the operation starts ({@link #declining} for those).
     *
     * @param declined set once a question was declined (by the operation or a task it wraps with {@link #declining})
     */
    public static <T, X extends Throwable> T declineDuring(AtomicBoolean declined, ScopedValue.CallableOp<T, X> operation)
            throws X {
        return ScopedValue.where(DECLINED, declined).call(operation);
    }

    /**
     * The task, answering no to every question it asks without asking the user (for a thread of its own). Called in
     * {@link #declineDuring}, the questions it declines are told to that operation.
     */
    public static Runnable declining(Runnable task) {
        AtomicBoolean declined = DECLINED.orElse(new AtomicBoolean());
        return () -> ScopedValue.where(DECLINED, declined).run(task);
    }

    /**
     * Puts the question to the handler and waits for the answer, one question at a time. No without asking: in an
     * operation that declines, without handler, on a thread that must not wait. No once the thread is interrupted
     * (its run is cancelled) while it waits: the question is withdrawn and the interrupt stays set.
     */
    public static boolean ask(String question) {
        if (DECLINED.isBound()) {
            DECLINED.get().set(true);
            return false;
        }
        ConfirmationHandler current = handler;
        if (current == null || !current.canWaitOnCurrentThread()) {
            return false;
        }
        try {
            ONE_AT_A_TIME.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            current = handler; // replaced while this question waited for the previous one
            return current != null && current.canWaitOnCurrentThread() && waitFor(current, question);
        } finally {
            ONE_AT_A_TIME.unlock();
        }
    }

    private static boolean waitFor(ConfirmationHandler current, String question) {
        CompletableFuture<Boolean> answer;
        try {
            answer = current.ask(question);
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "the question of a plugin could not be put: answered no", e);
            return false;
        }
        if (answer == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(answer.get());
        } catch (InterruptedException e) {
            answer.cancel(true); // the handler withdraws the question
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            LOGGER.log(System.Logger.Level.WARNING, "the question of a plugin got no answer: answered no", e.getCause());
            return false;
        } catch (CancellationException e) {
            return false;
        }
    }
}
