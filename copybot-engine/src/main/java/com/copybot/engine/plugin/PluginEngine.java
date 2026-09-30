package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.exception.PluginNotFoundException;
import com.copybot.logger.CopybotLogger;
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

    private static final CopybotLogger LOG = CopybotLogger.getLogger(PluginEngine.class);

    // replaced as a whole, once, by load(): readers never see a partially filled list
    private static volatile List<PluginDefinition> loadedPlugins = List.of();
    private static volatile List<PluginDefinition> errorPlugins = List.of();

    /** Directories of the first (and only) load, null before it. Guarded by the class lock. */
    private static List<Path> loadedFrom;

    /**
     * Loads the plugins of these directories. Thread-safe and idempotent: only the first successful call
     * of the JVM loads (JPMS module layers cannot be unloaded); later calls are ignored, with a warning in
     * the log when they ask for other directories. A first call that failed can be retried.
     */
    public static synchronized void load(Path pluginDir, List<Path> devPluginDirs) {
        List<Path> requested = new ArrayList<>();
        requested.add(pluginDir.toAbsolutePath().normalize());
        devPluginDirs.forEach(dir -> requested.add(dir.toAbsolutePath().normalize()));
        if (loadedFrom != null) {
            if (!loadedFrom.equals(requested)) {
                LOG.warn("plugin.load.ignored", requested, loadedFrom);
            }
            return;
        }

        PluginLoader pl = new PluginLoader();

        pl.resolve(FileUtil.listDirectory(pluginDir), false);
        pl.resolve(devPluginDirs, true);

        var allPlugins = pl.load();
        allPlugins.sort(Comparator
                .comparing(PluginDefinition::getName)
                .thenComparing(PluginDefinition::getVersion, Comparator.reverseOrder()));

        List<PluginDefinition> loaded = new ArrayList<>();
        List<PluginDefinition> errors = new ArrayList<>();
        for (PluginDefinition pluginDefinition : allPlugins) {
            if (pluginDefinition.getErrorMessage() == null) {
                loaded.add(pluginDefinition);
            } else {
                errors.add(pluginDefinition);
            }
        }
        loadedPlugins = Collections.unmodifiableList(loaded);
        errorPlugins = Collections.unmodifiableList(errors);
        loadedFrom = List.copyOf(requested);
    }

    // visible for tests: forgets the load of the JVM, so that the next load() really loads again
    static synchronized void resetForTest() {
        loadedPlugins = List.of();
        errorPlugins = List.of();
        loadedFrom = null;
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
