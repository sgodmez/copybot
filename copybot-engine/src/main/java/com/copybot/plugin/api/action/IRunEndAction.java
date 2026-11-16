package com.copybot.plugin.api.action;

import java.time.Duration;

/**
 * An action run once at the end of a run that was launched to copy, whatever its outcome (success, error,
 * cancelled): e.g. a notification or an archive. The engine calls the actions of the pipeline's {@code runEnd} list
 * that accept the outcome, in order, one at a time, each on its own thread with its time limit; a failure or a
 * timeout is turned into a warning of the run and the next action runs. The usual lifecycle of {@link IAction}
 * ({@code loadConfig}, {@code configSchema}...) applies; the per-item methods are never called.
 */
public interface IRunEndAction extends IAction {

    /** What this run did: see {@link RunReport}. The context takes what the action publishes or warns. */
    void runEnded(RunReport report, RunEndContext context);

    /** The time the action may take when the pipeline does not say; {@link Duration#ZERO} means no limit. */
    default Duration defaultTimeout() {
        return Duration.ofSeconds(60);
    }
}
