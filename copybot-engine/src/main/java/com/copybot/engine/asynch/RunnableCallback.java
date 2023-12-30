package com.copybot.engine.asynch;

import java.util.function.Consumer;

public final class RunnableCallback implements Runnable {
    private Runnable runnable;
    private Runnable successCallback;
    private Consumer<Throwable> errorCallback;

    public RunnableCallback(Runnable runnable, Runnable successCallback, Consumer<Throwable> errorCallback) {
        this.runnable = runnable;
        this.successCallback = successCallback;
        this.errorCallback = errorCallback;
    }

    @Override
    public void run() {
        try {
            runnable.run();
        } catch (Throwable t) {
            errorCallback.accept(t);
            return;
        }
        successCallback.run();
    }
}
