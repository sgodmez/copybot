package com.copybot.engine.plugin.report;

import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * The plugins of this JVM as they were loaded, for diagnosis (spec plugins-view §1).
 *
 * @param configFile           the configuration file read at startup
 * @param pluginPath           the plugin directory of the load, absolute
 * @param pluginPathConfigured false when the configuration declares none (default ./plugins)
 * @param devPluginPaths       the configured development directories, absolute, existing or not
 * @param warnings             translated header warnings (a directory that does not exist)
 * @param plugins              errors first, then actions failed, ignored, loaded; then by name, most recent first
 */
public record PluginReport(Path configFile, Path pluginPath, boolean pluginPathConfigured, List<Path> devPluginPaths,
                           List<String> warnings, List<PluginEntry> plugins) {

    public PluginReport {
        devPluginPaths = List.copyOf(devPluginPaths);
        warnings = List.copyOf(warnings);
        plugins = List.copyOf(plugins);
    }

    /** The whole report as plain text, to paste in a ticket. */
    public String toText() {
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        out.append(ResourcesEngine.getString("plugin.report.text.config", configFile)).append(nl);
        out.append(ResourcesEngine.getString(pluginPathConfigured ? "plugin.report.text.plugin-path" : "plugin.report.text.plugin-path-default", pluginPath)).append(nl);
        for (Path dev : devPluginPaths) {
            out.append(ResourcesEngine.getString("plugin.report.text.dev-path", dev)).append(nl);
        }
        for (String warning : warnings) {
            out.append("! ").append(warning).append(nl);
        }
        for (PluginEntry p : plugins) {
            out.append(nl).append("== ").append(p.name()).append(p.version() != null ? " " + p.version() : "")
                    .append(" [").append(p.status()).append(", ").append(p.source()).append("]").append(nl);
            if (p.path() != null) {
                out.append("   ").append(p.path()).append(nl);
            }
            if (p.message() != null) {
                out.append("   ").append(p.message()).append(nl);
            }
            for (ModuleEntry m : p.modules()) {
                out.append("   ").append(ResourcesEngine.getString("plugin.report.text.module")).append(' ')
                        .append(m.name()).append(m.version() != null ? " " + m.version() : "")
                        .append(m.main() ? " *" : "").append(m.automatic() ? " (automatic)" : "")
                        .append(m.location() != null ? "  " + m.location() : "").append(nl);
            }
            for (String dependency : p.pluginDependencies()) {
                out.append("   ").append(ResourcesEngine.getString("plugin.report.text.depends-on", dependency)).append(nl);
            }
            for (String missing : p.missingRequires()) {
                out.append("   ").append(ResourcesEngine.getString("plugin.report.text.missing", missing)).append(nl);
            }
            for (ActionEntry a : p.actions()) {
                out.append("   ").append(a.type()).append(' ').append(a.code())
                        .append(a.name().equals(a.code()) ? "" : " (" + a.name() + ")").append(nl);
            }
        }
        return out.toString();
    }
}