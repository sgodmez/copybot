package com.copybot.engine.plugin;

import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.engine.utils.FileUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PluginEngine {

    private static List<PluginDefinition> pluginDefinitions = new ArrayList<>();

    public static void load(Path pluginDir) {
        PluginLoader pl = new PluginLoader();

        pl.resolve(FileUtil.listDirectory(pluginDir), false);
        pl.resolve(List.of(Path.of("C:\\Users\\Steven\\IdeaProjects\\copybot\\copybot-plugin\\copybot-plugin-optional\\copybot-plugin-metadata-extractor\\target")), true);// test dev

        pluginDefinitions = pl.load();
    }

    public static List<PluginDefinition> getPluginDefinitions() {
        return pluginDefinitions;
    }
}
