package com.copybot.engine.plugin;

import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.engine.utils.FileUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class PluginEngine {

    private static List<PluginDefinition> loadedPlugins = new ArrayList<>();
    private static List<PluginDefinition> errorPlugins = new ArrayList<>();

    public static void load(Path pluginDir) {
        PluginLoader pl = new PluginLoader();

        pl.resolve(FileUtil.listDirectory(pluginDir), false);
        pl.resolve(List.of(Path.of("C:\\Users\\Steven\\IdeaProjects\\copybot\\copybot-plugin\\copybot-plugin-optional\\copybot-plugin-metadata-extractor\\target")), true);// test dev

        var allPlugins = pl.load();
        allPlugins.sort(Comparator.comparing(PluginDefinition::getName).thenComparing(PluginDefinition::getVersion));

        for (PluginDefinition pluginDefinition : allPlugins) {
            if (pluginDefinition.getErrorMessage() == null) {
                loadedPlugins.add(pluginDefinition);
            } else {
                errorPlugins.add(pluginDefinition);
            }
        }
        loadedPlugins = Collections.unmodifiableList(loadedPlugins);
        errorPlugins = Collections.unmodifiableList(errorPlugins);
    }

    public static List<PluginDefinition> getLoadedPlugins() {
        return loadedPlugins;
    }

    public static List<PluginDefinition> getErrorPlugins() {
        return errorPlugins;
    }
}
