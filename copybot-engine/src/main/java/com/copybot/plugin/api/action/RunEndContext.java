package com.copybot.plugin.api.action;

/** What the engine gives an end-of-run action to tell the run about its work. */
public interface RunEndContext {

    /** Makes a value available to the actions that follow, in {@link RunReport#outputs()}. */
    void publish(String key, String value);

    /** Adds a warning to the run; the action goes on. */
    void warn(String message);
}
