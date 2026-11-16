package com.copybot.ui.plugins;

/** Where an official plugin stands compared with the loaded plugins. */
public enum PluginState {
    NOT_INSTALLED,
    UP_TO_DATE,
    /** A newer version of the catalogue, compatible with this engine. */
    UPDATE_AVAILABLE,
    /**
     * Installed but refused at loading for its API, with no compatible update; a working plugin whose catalogue entry
     * needs a newer engine is {@link #UP_TO_DATE} ({@link PluginRow#newerVersionNeedsANewerEngine}).
     */
    INSTALLED_INCOMPATIBLE,
    /** Not installed and built for another engine: shown greyed, never offered. */
    INCOMPATIBLE
}
