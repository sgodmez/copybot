package com.copybot.engine.plugin.report;

/** The outcome of loading one plugin directory. */
public enum PluginStatus {
    /** Loaded, actions listed. */
    LOADED,
    /** Loaded, but listing its actions fails: a step naming it fails too. */
    ACTIONS_FAILED,
    /** Not loaded: see the message. */
    ERROR,
    /** Sound but not loaded: a duplicate, or an older revision of a loaded plugin. */
    IGNORED
}
