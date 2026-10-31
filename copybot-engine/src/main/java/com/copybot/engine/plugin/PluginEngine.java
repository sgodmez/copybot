package com.copybot.engine.plugin;

import com.copybot.config.SecretReferences;
import com.copybot.config.SecretStore;
import com.copybot.engine.listing.GroupingPresetRegistry;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.PluginLoader;
import com.copybot.exception.PluginNotFoundException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.IInAction;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PluginEngine {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(PluginEngine.class);

    // replaced as a whole, once, by load(): readers never see a partially filled list
    private static volatile List<PluginDefinition> loadedPlugins = List.of();
    private static volatile List<PluginDefinition> errorPlugins = List.of();
    // every definition of the load, loaded or not, in the sorted order
    private static volatile List<PluginDefinition> allPlugins = List.of();

    /** The broken plugins and actions already warned of by the catalog, built again at each editor opening. */
    private static final Set<String> CATALOG_WARNED = ConcurrentHashMap.newKeySet();

    /** The store of the {@code ${secret:name}} references of the action configurations. */
    private static volatile Supplier<SecretStore> secrets = SecretStore::forCurrentUser;

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

        var all = pl.load();
        all.sort(Comparator
                .comparing(PluginDefinition::getName)
                .thenComparing(PluginDefinition::getVersion, VersionUtil.VERSION_ORDER.reversed())); // most recent first

        List<PluginDefinition> loaded = new ArrayList<>();
        List<PluginDefinition> errors = new ArrayList<>();
        for (PluginDefinition pluginDefinition : all) {
            if (pluginDefinition.getErrorMessage() == null) {
                loaded.add(pluginDefinition);
            } else {
                errors.add(pluginDefinition);
                LOG.warn("plugin.load.not-loaded", pluginDefinition.getName(), pluginDefinition.getPath(), pluginDefinition.getErrorMessage());
            }
        }
        loadedPlugins = Collections.unmodifiableList(loaded);
        errorPlugins = Collections.unmodifiableList(errors);
        allPlugins = Collections.unmodifiableList(new ArrayList<>(all));
        loadedFrom = List.copyOf(requested);
    }

    // visible for tests: forgets the load of the JVM, so that the next load() really loads again
    static synchronized void resetForTest() {
        loadedPlugins = List.of();
        errorPlugins = List.of();
        allPlugins = List.of();
        loadedFrom = null;
    }

    public static List<PluginDefinition> getLoadedPlugins() {
        return loadedPlugins;
    }

    /** Every definition of the load (loaded or not), empty before it. */
    public static List<PluginDefinition> getAllPlugins() {
        return allPlugins;
    }

    public static List<PluginDefinition> getErrorPlugins() {
        return errorPlugins;
    }

    /**
     * Every action of the loaded plugins (desktop-ui spec, part 4), in plugin order (name, then most recent
     * version first) then step type (IN, ANALYZE, PROCESS, OUT, RUN_END), texts in the current language. Empty
     * before {@link #load}. A plugin whose actions cannot be listed (e.g. a missing class) has none, with a
     * warning in the log (once per JVM), and is one of the {@link PluginCatalog#failedPlugins()}: it never
     * hides the others. The grouping presets are those a run can cite ({@link GroupingPresetRegistry#current()}),
     * texts in the current language, even those of a plugin whose actions cannot be listed; a plugin whose presets
     * cannot be read is a failed plugin too.
     */
    public static PluginCatalog catalog() {
        List<PluginDefinition> plugins = loadedPlugins;
        // the registry of this very list, even if a load replaces it meanwhile
        return catalogOf(plugins, GroupingPresetRegistry.current(plugins));
    }

    // visible for tests: the catalog of these plugins
    static PluginCatalog catalogOf(List<PluginDefinition> plugins) {
        return catalogOf(plugins, GroupingPresetRegistry.of(plugins));
    }

    private static PluginCatalog catalogOf(List<PluginDefinition> plugins, GroupingPresetRegistry registry) {
        List<CatalogAction> actions = new ArrayList<>();
        List<PluginCatalog.FailedPlugin> failed = new ArrayList<>();
        List<CatalogPreset> presets = new ArrayList<>();
        Set<String> named = new HashSet<>();
        for (PluginDefinition plugin : plugins) {
            // of the versions of a plugin, the first one (the most recent) brings the presets, as in the registry
            boolean bringsPresets = named.add(plugin.getName());
            if (bringsPresets && !registry.hasPlugin(plugin.getName())) {
                // its presets cannot be read: the registry warned and leaves the plugin out
                failed.add(new PluginCatalog.FailedPlugin(plugin.getName(), plugin.getVersion()));
                continue;
            }
            List<CatalogAction> pluginActions = new ArrayList<>();
            try {
                IPlugin instance = plugin.getPluginInstance();
                addActions(pluginActions, plugin, StepType.IN, instance.getInActions());
                addActions(pluginActions, plugin, StepType.ANALYZE, instance.getAnalyzeActions());
                addActions(pluginActions, plugin, StepType.PROCESS, instance.getProcessActions());
                addActions(pluginActions, plugin, StepType.OUT, instance.getOutActions());
                addActions(pluginActions, plugin, StepType.RUN_END, instance.getRunEndActions());
                actions.addAll(pluginActions);
            } catch (LinkageError | RuntimeException e) {
                if (CATALOG_WARNED.add(plugin.getName() + ":" + plugin.getVersion())) {
                    LOG.warn(e, "plugin.catalog.failed", plugin.getName(), plugin.getVersion(), String.valueOf(e));
                }
                failed.add(new PluginCatalog.FailedPlugin(plugin.getName(), plugin.getVersion()));
            }
            if (bringsPresets) {
                // even when its actions failed: the registry has them, a run accepts them
                addPresets(presets, plugin, registry);
            }
        }
        return new PluginCatalog(actions, failed, presets, registry.plugins());
    }

    private static void addPresets(List<CatalogPreset> presets, PluginDefinition plugin,
                                   GroupingPresetRegistry registry) {
        CombinedResourceBundle bundle;
        try {
            bundle = plugin.getPluginInstance().getResourceBundle();
        } catch (LinkageError | RuntimeException e) {
            bundle = null; // the codes stand for the texts
        }
        for (GroupingPresetRegistry.PluginPreset preset : registry.pluginPresets()) {
            if (preset.plugin().equals(plugin.getName())) {
                String keyPrefix = "plugin." + preset.pluginCode() + ".preset." + preset.code();
                presets.add(new CatalogPreset(preset.plugin(), preset.pluginCode(), preset.version(), preset.code(),
                        text(bundle, keyPrefix + ".name").orElse(preset.code()),
                        text(bundle, keyPrefix + ".description").orElse(""), preset.rules()));
            }
        }
    }

    private static void addActions(List<CatalogAction> actions, PluginDefinition plugin, StepType type,
                                   List<? extends ActionDefinition<?>> definitions) {
        IPlugin instance = plugin.getPluginInstance();
        CombinedResourceBundle bundle = instance.getResourceBundle();
        for (ActionDefinition<?> definition : definitions) {
            String keyPrefix = "plugin." + instance.getPluginCode() + "." + definition.actionCode();
            Described described = describe(plugin, definition);
            ConfigSchema schema = described.schema() == null ? null : described.schema().withKeyPrefix(keyPrefix);
            Map<String, String> texts = new HashMap<>();
            if (schema != null) {
                for (ConfigField field : schema.allFields()) {
                    text(bundle, field.labelKey()).ifPresent(t -> texts.put(field.labelKey(), t));
                    text(bundle, field.descriptionKey()).ifPresent(t -> texts.put(field.descriptionKey(), t));
                    for (String value : field.enumValues()) {
                        text(bundle, field.valueKey(value)).ifPresent(t -> texts.put(field.valueKey(value), t));
                    }
                }
            }
            actions.add(new CatalogAction(plugin.getName(), instance.getPluginCode(), plugin.getVersion(),
                    definition.actionCode(), type,
                    text(bundle, keyPrefix + ".name").orElse(definition.actionCode()),
                    text(bundle, keyPrefix + ".description").orElse(""),
                    schema, texts, described.signalsFolders()));
        }
    }

    /** What a fresh instance tells of itself: its schema (null: none) and, for an input, whether it signals folders. */
    private record Described(ConfigSchema schema, boolean signalsFolders) {
    }

    /**
     * What a fresh instance tells; an action that cannot be instantiated or describe itself describes nothing
     * (warned in the log; the step resolution reports the failure again, with its context).
     */
    private static Described describe(PluginDefinition plugin, ActionDefinition<?> definition) {
        try {
            IAction action = newAction(plugin, definition);
            ConfigSchema schema = action.configSchema().orElse(null);
            return new Described(schema, action instanceof IInAction in && in.signalsFolders());
        } catch (LinkageError | RuntimeException e) {
            if (CATALOG_WARNED.add(plugin.getName() + ":" + plugin.getVersion() + ":" + definition.actionCode())) {
                LOG.warn(e, "plugin.catalog.no-schema", plugin.getName(), definition.actionCode(), String.valueOf(e));
            }
            return new Described(null, false);
        }
    }

    /** The text of the key in the plugin bundle (which falls back on the engine one), empty when absent. */
    private static Optional<String> text(CombinedResourceBundle bundle, String key) {
        if (bundle == null) {
            return Optional.empty();
        }
        String value = bundle.getString(key);
        return value.equals("%" + key) ? Optional.empty() : Optional.of(value); // "%key": no such key
    }

    /**
     * A new configured instance of the step's action, whatever its type. The {@code ${secret:name}} and
     * {@code ${env:NAME}} references of its configuration are resolved just before {@link IAction#loadConfig}
     * (spec run-end, Secrets): a reference that cannot be resolved refuses the step by its name, and an invalid
     * configuration never shows a resolved value in its error. The step's {@link PipelineStep#getRedactor()} hides
     * them in what the action says later.
     */
    public static <A extends IAction> PipelineStep<A> resolve(PipelineStepConfig stepConfig, Class<A> actionClass) {
        StepType type = StepType.getType(actionClass);
        // if config contains version, get plugin with desired version
        // else get most recent (list is ordered with most recent first)
        var pluginDef = loadedPlugins.stream()
                .filter(p -> pluginMatch(p, stepConfig))
                .findFirst()
                .orElseThrow(() -> new PluginNotFoundException(stepConfig.getDisplayName()));

        IAction instance = newAction(pluginDef, pluginDef.findAction(stepConfig.action(), type));
        if (!actionClass.isInstance(instance)) { // an action registered under the wrong step type
            throw new IllegalStateException(instance.getClass().getName() + " is not a " + actionClass.getSimpleName());
        }
        A action = actionClass.cast(instance);
        // the action alone gets the values of ${secret:} and ${env:}; the step keeps the references
        SecretReferences.Resolved config = SecretReferences.resolve(stepConfig.actionConfig(), secrets);
        try {
            action.loadConfig(config.config());
        } catch (RuntimeException e) {
            throw config.redact(e);
        }
        return new PipelineStep<>(pluginDef.getPluginInstance(), action, stepConfig, config);
    }

    // visible for tests: the secrets the references are resolved with, the current user's again with null
    static void useSecretsForTest(SecretStore store) {
        secrets = store == null ? SecretStore::forCurrentUser : () -> store;
    }

    /** A new instance of the action, chained with its plugin ({@link IAction#setPlugin}) before anything else. */
    private static IAction newAction(PluginDefinition plugin, ActionDefinition<?> definition) {
        IAction action = definition.getInstance();
        action.setPlugin(plugin.getPluginInstance());
        return action;
    }

    private static boolean pluginMatch(PluginDefinition plugin, PipelineStepConfig stepConfig) {
        String stepPluginName = stepConfig.plugin() == null || stepConfig.plugin().isBlank() ? CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME : stepConfig.plugin();
        return plugin.getName().equals(stepPluginName)
                && (
                plugin.getVersion() == null // a plugin built without version, or the engine run without one
                        || VersionUtil.isCompatible(plugin.getVersion(), stepConfig.version(), true)
        );
    }
}
