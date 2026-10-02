package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.VersionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Builds the {@link PluginReport} of loaded plugin definitions. */
public final class PluginReports {

    private static final Map<PluginStatus, Integer> RANK = Map.of(
            PluginStatus.ERROR, 0, PluginStatus.ACTIONS_FAILED, 1, PluginStatus.IGNORED, 2, PluginStatus.LOADED, 3);

    private PluginReports() {
    }

    public static PluginReport of(Path configFile, Path pluginPath, boolean pluginPathConfigured,
                                  List<Path> devPluginPaths, List<PluginDefinition> definitions) {
        Path plugins = absolute(pluginPath);
        List<Path> devs = devPluginPaths.stream().map(PluginReports::absolute).toList();
        List<String> warnings = new ArrayList<>();
        if (!Files.isDirectory(plugins)) {
            warnings.add(ResourcesEngine.getString("plugin.report.path-missing", plugins));
        }
        for (Path dev : devs) {
            if (!Files.isDirectory(dev)) {
                warnings.add(ResourcesEngine.getString("plugin.report.dev-path-missing", dev));
            }
        }
        List<PluginEntry> entries = definitions.stream()
                .map(PluginReports::entry)
                .sorted(Comparator.<PluginEntry>comparingInt(e -> RANK.get(e.status()))
                        .thenComparing(PluginEntry::name)
                        .thenComparing(PluginEntry::version, Comparator.nullsLast(VersionUtil.VERSION_ORDER.reversed())))
                .toList();
        return new PluginReport(absolute(configFile), plugins, pluginPathConfigured, devs, warnings, entries);
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static PluginEntry entry(PluginDefinition definition) {
        PluginStatus status;
        String message = definition.getErrorMessage();
        List<ActionEntry> actions = List.of();
        if (definition.isIgnored()) {
            status = PluginStatus.IGNORED;
        } else if (!definition.isActive()) {
            status = PluginStatus.ERROR;
        } else {
            try {
                actions = actions(definition.getPluginInstance());
                status = PluginStatus.LOADED;
            } catch (LinkageError | RuntimeException e) {
                status = PluginStatus.ACTIONS_FAILED;
                message = ResourcesEngine.getString("plugin.report.actions-failed", String.valueOf(e));
            }
        }
        return new PluginEntry(definition.getName(), definition.getVersion(), definition.getSource(), definition.getPath(),
                status, message, definition.getModules(), definition.getPluginDependencies(),
                definition.getMissingRequires(), actions);
    }

    private static List<ActionEntry> actions(IPlugin plugin) {
        List<ActionEntry> actions = new ArrayList<>();
        add(actions, plugin, StepType.IN, plugin.getInActions());
        add(actions, plugin, StepType.ANALYZE, plugin.getAnalyzeActions());
        add(actions, plugin, StepType.PROCESS, plugin.getProcessActions());
        add(actions, plugin, StepType.OUT, plugin.getOutActions());
        return actions;
    }

    private static void add(List<ActionEntry> actions, IPlugin plugin, StepType type, List<? extends ActionDefinition<?>> definitions) {
        CombinedResourceBundle bundle = plugin.getResourceBundle();
        for (ActionDefinition<?> definition : definitions) {
            String key = "plugin." + plugin.getPluginCode() + "." + definition.actionCode() + ".name";
            String name = bundle != null ? bundle.getString(key) : null;
            actions.add(new ActionEntry(type, definition.actionCode(),
                    name == null || name.equals("%" + key) ? definition.actionCode() : name));
        }
    }
}
