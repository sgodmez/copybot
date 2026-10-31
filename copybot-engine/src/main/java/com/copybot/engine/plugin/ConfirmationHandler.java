package com.copybot.engine.plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Who answers the questions of the plugins ({@link com.copybot.plugin.api.UserConfirmation}): the window of the
 * desktop app, the command line. Installed by {@link com.copybot.engine.CopybotEngine#setConfirmationHandler}.
 */
@FunctionalInterface
public interface ConfirmationHandler {

    /**
     * Puts the question to the user, without waiting for the answer: the engine waits on the future. The engine
     * cancels the future when the plugin gives up waiting (its run is cancelled): the question is then withdrawn
     * (e.g. its dialog closed). Never called twice at the same time.
     *
     * @param question one line, from the plugin
     * @return true when the user agrees; completed exceptionally or cancelled: no
     */
    CompletableFuture<Boolean> ask(String question);

    /**
     * False when the current thread must not wait for an answer (e.g. the thread that shows the questions): the
     * engine then answers no without asking.
     */
    default boolean canWaitOnCurrentThread() {
        return true;
    }
}
