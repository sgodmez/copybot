package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.exception.PluginNotFoundException;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class PluginEngine {

    private static List<PluginDefinition> loadedPlugins = new ArrayList<>();
    private static List<PluginDefinition> errorPlugins = new ArrayList<>();

    public static void load(Path pluginDir, List<Path> devPluginDirs) {
        PluginLoader pl = new PluginLoader();

        pl.resolve(FileUtil.listDirectory(pluginDir), false);
        pl.resolve(devPluginDirs, true);

        var allPlugins = pl.load();
        allPlugins.sort(Comparator
                .comparing(PluginDefinition::getName)
                .thenComparing(PluginDefinition::getVersion, Comparator.reverseOrder()));

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


    public static <A extends IAction> PipelineStep<A> resolve(PipelineStepConfig stepConfig, Class<A> actionClass) {
        StepType type = StepType.getType(actionClass);
        // if config contains version, get plugin with desired version
        // else get most recent (list is ordered with most recent first)
        var pluginDef = loadedPlugins.stream()
                .filter(p -> pluginMatch(p, stepConfig))
                .findFirst()
                .orElseThrow(() -> new PluginNotFoundException(stepConfig.getDisplayName()));

        var actionDef = pluginDef.findAction(stepConfig.action(), type);
        IAction actionInstance = actionDef.getInstance();
        actionInstance.loadConfig(stepConfig.actionConfig());
        return new PipelineStep(pluginDef.getPluginInstance(), actionInstance, stepConfig);
    }


    private static boolean pluginMatch(PluginDefinition plugin, PipelineStepConfig stepConfig) {
        String stepPluginName = stepConfig.plugin() == null || stepConfig.plugin().isBlank() ? CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME : stepConfig.plugin();
        return plugin.getName().equals(stepPluginName)
                && (
                plugin.getVersion() == null // fixme embedded plugin have no version
                        || VersionUtil.isCompatible(plugin.getVersion(), stepConfig.version(), true)
        );
    }
}
