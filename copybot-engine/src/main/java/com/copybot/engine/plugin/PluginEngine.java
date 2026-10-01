package com.copybot.engine.plugin;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.exception.PluginNotFoundException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.resources.CombinedResourceBundle;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Every action of the loaded plugins (desktop-ui spec, part 4), in plugin order (name, then most recent
     * version first) then step type (IN, ANALYZE, PROCESS, OUT), texts in the current language. Empty
     * before {@link #load}. A plugin whose actions cannot be listed (e.g. a missing class) is left out, with
     * a warning in the log: it never hides the others.
     */
    public static List<CatalogAction> catalog() {
        return catalogOf(loadedPlugins);
    }

    // visible for tests: the catalog of these plugins
    static List<CatalogAction> catalogOf(List<PluginDefinition> plugins) {
        List<CatalogAction> actions = new ArrayList<>();
        for (PluginDefinition plugin : plugins) {
            List<CatalogAction> pluginActions = new ArrayList<>();
            try {
                IPlugin instance = plugin.getPluginInstance();
                addActions(pluginActions, plugin, StepType.IN, instance.getInActions());
                addActions(pluginActions, plugin, StepType.ANALYZE, instance.getAnalyzeActions());
                addActions(pluginActions, plugin, StepType.PROCESS, instance.getProcessActions());
                addActions(pluginActions, plugin, StepType.OUT, instance.getOutActions());
            } catch (LinkageError | RuntimeException e) {
                LOG.warn(e, "plugin.catalog.failed", plugin.getName(), plugin.getVersion(), String.valueOf(e));
                continue;
            }
            actions.addAll(pluginActions);
        }
        return List.copyOf(actions);
    }

    private static void addActions(List<CatalogAction> actions, PluginDefinition plugin, StepType type,
                                   List<? extends ActionDefinition<?>> definitions) {
        IPlugin instance = plugin.getPluginInstance();
        CombinedResourceBundle bundle = instance.getResourceBundle();
        for (ActionDefinition<?> definition : definitions) {
            String keyPrefix = "plugin." + instance.getPluginCode() + "." + definition.actionCode();
            ConfigSchema schema = schemaOf(plugin, definition).map(s -> s.withKeyPrefix(keyPrefix)).orElse(null);
            Map<String, String> texts = new HashMap<>();
            if (schema != null) {
                for (ConfigField field : schema.allFields()) {
                    text(bundle, field.labelKey()).ifPresent(t -> texts.put(field.labelKey(), t));
                    text(bundle, field.descriptionKey()).ifPresent(t -> texts.put(field.descriptionKey(), t));
                }
            }
            actions.add(new CatalogAction(plugin.getName(), instance.getPluginCode(), plugin.getVersion(),
                    definition.actionCode(), type,
                    text(bundle, keyPrefix + ".name").orElse(definition.actionCode()),
                    text(bundle, keyPrefix + ".description").orElse(""),
                    schema, texts));
        }
    }

    /**
     * The schema of a fresh instance; an action that cannot be instantiated or describe itself describes
     * nothing (warned in the log; the step resolution reports the failure again, with its context).
     */
    private static Optional<ConfigSchema> schemaOf(PluginDefinition plugin, ActionDefinition<?> definition) {
        try {
            return definition.getInstance().configSchema();
        } catch (LinkageError | RuntimeException e) {
            LOG.warn(e, "plugin.catalog.no-schema", plugin.getName(), definition.actionCode(), String.valueOf(e));
            return Optional.empty();
        }
    }

    /** The text of the key in the plugin bundle (which falls back on the engine one), empty when absent. */
    private static Optional<String> text(CombinedResourceBundle bundle, String key) {
        if (bundle == null) {
            return Optional.empty();
        }
        String value = bundle.getString(key);
        return value.startsWith("%") ? Optional.empty() : Optional.of(value); // "%key": no such key
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
