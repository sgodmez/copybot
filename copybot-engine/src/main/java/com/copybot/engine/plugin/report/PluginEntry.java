package com.copybot.engine.plugin.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One plugin directory as loaded (spec plugins-view §1).
 *
 * @param version            null for the embedded plugin or a module without version
 * @param path               the plugin directory, null for the embedded plugin
 * @param message            the cause, translated; null when LOADED
 * @param pluginDependencies "name version" of the plugins its layer is built on
 * @param missingRequires    "module:version" (or "module") nothing loaded provides
 * @param actions            empty unless LOADED
 */
public record PluginEntry(String name, String version, PluginSource source, Path path, PluginStatus status,
                          String message, List<ModuleEntry> modules, List<String> pluginDependencies,
                          List<String> missingRequires, List<ActionEntry> actions) {

    public PluginEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        modules = List.copyOf(modules);
        pluginDependencies = List.copyOf(pluginDependencies);
        missingRequires = List.copyOf(missingRequires);
        actions = List.copyOf(actions);
    }
}
