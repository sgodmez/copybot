package com.copybot.ui.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The work runs in the background, then exactly one callback runs on the "UI" thread. */
public class BackgroundTest {

    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-background"));
    private final ExecutorService ui = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-ui"));

    @AfterEach
    void stop() {
        background.shutdownNow();
        ui.shutdownNow();
    }

    @Test
    public void theResultReachesOnSuccessOnTheUiThread() throws Exception {
        AtomicReference<String> workThread = new AtomicReference<>();
        CompletableFuture<String> success = new CompletableFuture<>();
        Background.run(background, ui, () -> {
                    workThread.set(Thread.currentThread().getName());
                    return "done";
                },
                result -> success.complete(result + " on " + Thread.currentThread().getName()),
                failure -> success.completeExceptionally(new AssertionError("no failure expected", failure)));
        assertEquals("done on test-ui", success.get(5, TimeUnit.SECONDS));
        assertEquals("test-background", workThread.get());
    }

    @Test
    public void anExceptionReachesOnErrorOnTheUiThread() throws Exception {
        CompletableFuture<String> error = new CompletableFuture<>();
        Background.run(background, ui, () -> {
                    throw new IllegalStateException("broken");
                },
                result -> error.completeExceptionally(new AssertionError("no result expected")),
                failure -> error.complete(failure.getMessage() + " on " + Thread.currentThread().getName()));
        assertEquals("broken on test-ui", error.get(5, TimeUnit.SECONDS));
    }

    /** A LinkageError of a plugin, say: never lost, the caller still gets its callback. */
    @Test
    public void anErrorReachesOnErrorToo() throws Exception {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        Background.run(background, ui, () -> {
                    throw new NoClassDefFoundError("plugin");
                },
                result -> error.completeExceptionally(new AssertionError("no result expected")),
                error::complete);
        assertInstanceOf(NoClassDefFoundError.class, error.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void aWorkWithoutResultGivesNull() throws Exception {
        CompletableFuture<Object> success = new CompletableFuture<>();
        Background.run(background, ui, () -> null, success::complete, success::completeExceptionally);
        assertNull(success.get(5, TimeUnit.SECONDS));
    }

    /** The application is stopping: onError at once, on the calling thread. */
    @Test
    public void aRefusedWorkReachesOnErrorAtOnce() {
        background.shutdown();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Background.run(background, ui, () -> "never", result -> fail("not run"), error::set);
        assertInstanceOf(RejectedExecutionException.class, error.get());
    }
}
