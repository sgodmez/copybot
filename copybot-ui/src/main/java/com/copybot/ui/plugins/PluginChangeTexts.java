package com.copybot.ui.plugins;

import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The texts about the plugins other plugins need (the confirmation of the changes, the missing plugins of a card),
 * kept out of the controllers so that they can be tested without JavaFX.
 */
public final class PluginChangeTexts {

    private PluginChangeTexts() {
    }

    /**
     * The lines of the confirmation: what is installed, updated, uninstalled, what comes along because it is needed,
     * and a warning for each plugin a removal leaves without one it needs.
     */
    public static List<String> changes(PluginDependencies.Plan plan) {
        List<String> lines = new ArrayList<>();
        List<PluginRow> chosen = plan.installOrUpdate().stream().filter(row -> !plan.added().contains(row)).toList();
        List<PluginRow> installs = chosen.stream().filter(row -> row.state() == PluginState.NOT_INSTALLED).toList();
        List<PluginRow> updates = chosen.stream().filter(row -> !installs.contains(row)).toList();
        addLine(lines, "plugins.confirm.install", installs);
        addLine(lines, "plugins.confirm.update", updates);
        for (PluginRow added : plan.added()) {
            String neededBy = plan.installOrUpdate().stream()
                    .filter(row -> row.entry().requires().stream().anyMatch(r -> r.code().equals(added.entry().code())))
                    .map(row -> row.entry().code()).collect(Collectors.joining(", "));
            lines.add(ResourcesEngine.getString("plugins.confirm.with",
                    added.entry().code() + " " + added.entry().version(), neededBy));
        }
        addLine(lines, "plugins.confirm.remove", plan.remove());
        for (PluginDependencies.Broken broken : plan.broken()) {
            lines.add(ResourcesEngine.getString("plugins.confirm.needed-by", broken.removed().entry().code(),
                    broken.dependent().entry().code()));
        }
        return lines;
    }

    /**
     * Why the changes are refused, one line per cause: a requirement the catalogue cannot meet, a needed plugin
     * chosen to be removed meanwhile. Empty when they can be applied.
     */
    public static List<String> refusals(PluginDependencies.Plan plan) {
        List<String> lines = new ArrayList<>();
        for (PluginDependencies.Unavailable u : plan.unavailable()) {
            lines.add(ResourcesEngine.getString("plugins.confirm.unavailable", u.row().entry().code(),
                    u.requirement().code(), u.requirement().version()));
        }
        for (PluginDependencies.Unavailable u : plan.neededRemoved()) {
            lines.add(ResourcesEngine.getString("plugins.confirm.needed-removed", u.row().entry().code(),
                    u.requirement().code()));
        }
        return lines;
    }

    /**
     * A missing requirement of a plugin as the loader names it ("module:version" or "module"): "the plugin exec
     * 2.0.0" when it is the main module of a plugin of the catalogue, else as it is.
     */
    public static String missing(String requirement, List<PluginRow> rows) {
        int colon = requirement.indexOf(':');
        String module = colon < 0 ? requirement : requirement.substring(0, colon);
        String version = colon < 0 ? "" : requirement.substring(colon + 1);
        Map<String, PluginRow> byModule = rows.stream()
                .collect(Collectors.toMap(row -> row.entry().module(), Function.identity(), (a, b) -> a));
        return Optional.ofNullable(byModule.get(module))
                .map(row -> ResourcesEngine.getString("plugins.card.missing-plugin", row.entry().code(), version)
                        .strip())
                .orElse(requirement);
    }

    private static void addLine(List<String> lines, String key, List<PluginRow> rows) {
        if (!rows.isEmpty()) {
            lines.add(ResourcesEngine.getString(key, rows.stream().map(row -> row.entry().code())
                    .collect(Collectors.joining(", "))));
        }
    }
}
