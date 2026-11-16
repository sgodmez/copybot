package com.copybot.engine.plugin;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The folder holding the data folders of the plugins ({@link com.copybot.plugin.api.PluginFolders}), set by the
 * engine when it starts: {@code <data folder of Copybot>/plugin-data}. Kept out of the plugin API so that no plugin
 * can move it.
 */
public final class PluginDataRoot {

    private static volatile Path root;

    private PluginDataRoot() {
    }

    /** @param folder the folder of the plugins' data; null: none (the temporary folder is used) */
    public static void set(Path folder) {
        root = folder == null ? null : folder.toAbsolutePath().normalize();
    }

    /** The folder set by the engine, empty outside the engine. */
    public static Optional<Path> get() {
        return Optional.ofNullable(root);
    }
}
