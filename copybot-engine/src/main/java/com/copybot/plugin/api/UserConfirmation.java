package com.copybot.plugin.api;

import com.copybot.engine.plugin.Confirmations;

/** A question a plugin puts to the user during a run (e.g. trust a host seen for the first time). */
public final class UserConfirmation {

    private UserConfirmation() {
    }

    /**
     * Asks the user and blocks until the answer; one question at a time, the others wait for their turn. The answer
     * is no, without asking: outside the app (a test, an integration), in the sample of the editor (a preview never
     * trusts anything; the sample tells the user that a run asks), and when the run is cancelled while the question
     * waits (the thread is left interrupted). The command line answers yes, with a warning showing the question.
     * <p>
     * Ask from the work of a run: {@code listFiles}, {@code beforeAll} or a step of an item. Not from
     * {@code afterAll}: a cancellation does not interrupt it, so its question would not be withdrawn.
     *
     * @param question one line in the language of the user, without any secret
     * @return true when the user agrees
     * @throws IllegalArgumentException the question is null or blank
     */
    public static boolean ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("a question is needed");
        }
        return Confirmations.ask(question);
    }
}
