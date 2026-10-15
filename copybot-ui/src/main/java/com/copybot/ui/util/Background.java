package com.copybot.ui.util;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * The background work of the views (engine operations, file access), never on the JavaFX thread: the work runs
 * on a background thread, then its result or its failure is handed to the JavaFX thread. The caller always gets
 * exactly one of the two callbacks, whatever the work throws (an Error too).
 */
public final class Background {

    /**
     * Daemon threads: a file check stuck on a disconnected share must not keep the application alive; the engine
     * operations are waited for by the engine's close() on exit.
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "copybot-ui-background");
        thread.setDaemon(true);
        return thread;
    });

    private Background() {
    }

    /** For the work chained on a future ({@code thenAcceptAsync}): the same threads. */
    public static Executor executor() {
        return EXECUTOR;
    }

    /** On exit: no new work is accepted (it then fails at once with a RejectedExecutionException). */
    public static void shutdown() {
        EXECUTOR.shutdown();
    }

    /**
     * Runs the work in the background, then onSuccess with its result (null for a work that returns nothing) or
     * onError with its failure, on the JavaFX thread. When the work cannot start (the application is stopping),
     * onError gets the RejectedExecutionException at once, on the calling thread.
     */
    public static <T> void run(Callable<T> work, Consumer<? super T> onSuccess, Consumer<? super Throwable> onError) {
        run(EXECUTOR, Platform::runLater, work, onSuccess, onError);
    }

    /** {@link #run(Callable, Consumer, Consumer)} on these threads (the plan operations, tests). */
    public static <T> void run(Executor background, Executor ui, Callable<T> work, Consumer<? super T> onSuccess,
                        Consumer<? super Throwable> onError) {
        try {
            background.execute(() -> {
                T result;
                try {
                    result = work.call();
                } catch (Throwable t) {
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    ui.execute(() -> onError.accept(t));
                    return;
                }
                ui.execute(() -> onSuccess.accept(result));
            });
        } catch (RejectedExecutionException e) {
            onError.accept(e);
        }
    }
}
