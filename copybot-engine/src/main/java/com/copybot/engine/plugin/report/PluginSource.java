package com.copybot.engine.plugin.report;

/** Where a plugin was found. */
public enum PluginSource {
    /** A sub-directory of the configured plugin path. */
    PLUGIN_PATH,
    /** A development directory (devPluginPaths): its classes/ and lib/ sub-directories. */
    DEV,
    /** The plugin built into the engine. */
    EMBEDDED
}
