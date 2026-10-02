package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.VersionUtil;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

public final class PluginLoader {
    List<LayerLoader> validLayers;
    List<LayerLoader> loadedLayers;
    List<LayerLoader> layersWithDependencies;
    private List<PluginDefinition> pluginDefinitions;

    public PluginLoader() {
        validLayers = new ArrayList<>();
        pluginDefinitions = new ArrayList<>();
        loadedLayers = new ArrayList<>();
        layersWithDependencies = new ArrayList<>();
    }

    public List<PluginDefinition> load() {
        loadNoDepPlugins(); // load no dependency plugins and populate layersWithDepenencies

        // resolve and load plugins with dependencies
        resolvePluginWithDependencies();

        // instantiate resolved plugins
        instanciateResolved();

        return pluginDefinitions;
    }

    public void resolve(List<Path> pluginsDirs, boolean isDev) {
        for (Path p : pluginsDirs) {
            var ll = isDev ? LayerLoader.ofDev(p) : LayerLoader.of(p);
            if (ll.getError() != null) {
                pluginDefinitions.add(PluginDefinition.ofError(ll));
                continue;
            }
            var samePreviousPluginOpt = validLayers.stream()
                    .filter(previousLl -> VersionUtil.sameMajorMinorModule(ll.getMainModuleDescriptor(), previousLl.getMainModuleDescriptor()))
                    .findFirst();
            if (samePreviousPluginOpt.isPresent()) {
                LayerLoader previousLl = samePreviousPluginOpt.get();
                // a plugin without version is older than any versioned one (VERSION_ORDER)
                int order = VersionUtil.VERSION_ORDER.compare(ll.getVersion(), previousLl.getVersion());
                if (order > 0) {
                    // new ll is a more recent revision version of this minor version
                    validLayers.remove(previousLl);
                    pluginDefinitions.add(PluginDefinition.ofIgnored(previousLl,
                            ResourcesEngine.getString("plugin.load.newer-revision", ll.getVersion(), ll.getPath())));
                } else if (order == 0) {
                    pluginDefinitions.add(PluginDefinition.ofIgnored(ll,
                            ResourcesEngine.getString("plugin.load.duplicate", previousLl.getPath())));
                    continue;
                } else {
                    pluginDefinitions.add(PluginDefinition.ofIgnored(ll,
                            ResourcesEngine.getString("plugin.load.newer-revision", previousLl.getVersion(), previousLl.getPath())));
                    continue;
                }
            }
            validLayers.add(ll);
        }
    }

    private void loadNoDepPlugins() {
        for (LayerLoader ll : validLayers) {
            if (ll.canBeLoaded()) { // no plugin dependency
                tryLoad(ll, List.of());
            } else { // need other plugin
                layersWithDependencies.add(ll);
            }
        }
    }

    private void resolvePluginWithDependencies() {
        boolean hasResolvedPlugin;
        do {
            hasResolvedPlugin = false;
            var it = layersWithDependencies.iterator();
            while (it.hasNext()) {
                LayerLoader ll = it.next();
                // find dependencies candidates in already loaded
                List<LayerLoader> candidates = getCandidates(ll);

                if (ll.canBeLoaded(candidates.stream().map(LayerLoader::getModuleLayer).toList())) {
                    it.remove(); // loaded or in error: either way, resolved
                    hasResolvedPlugin |= tryLoad(ll, candidates);
                }
            }
        } while (hasResolvedPlugin);

        // can't resolve theses
        markUnresolvedPlugin();
    }

    /**
     * Defines the layer of the plugin; a plugin whose modules do not resolve or cannot be defined (e.g. a package
     * in two of its modules) is in error, the others load. The plugins requiring it then miss a dependency.
     */
    private boolean tryLoad(LayerLoader ll, List<LayerLoader> parents) {
        try {
            ll.load(parents);
        } catch (RuntimeException e) { // ResolutionException, FindException, LayerInstantiationException...
            pluginDefinitions.add(PluginDefinition.ofError(ll, ResourcesEngine.getString("plugin.load.layer", describe(e))));
            return false;
        }
        loadedLayers.add(ll);
        return true;
    }

    private List<LayerLoader> getCandidates(LayerLoader ll) {
        return loadedLayers.stream()
                .filter(c -> ll.getRequires().stream().anyMatch(r -> VersionUtil.moduleCompatible(c.getMainModuleDescriptor(), r)))
                .toList();
    }

    private void markUnresolvedPlugin() {
        List<ModuleDescriptor> loadedModules = loadedLayers.stream().map(LayerLoader::getMainModuleDescriptor).toList();
        for (LayerLoader ll : layersWithDependencies) {
            List<String> missing = missingDependencyList(ll.getRequires(), loadedModules);
            pluginDefinitions.add(PluginDefinition.ofError(ll, ResourcesEngine.getString(
                    "plugin.load.missing-dependencies", String.join(", ", missing))).withMissingRequires(missing));
        }
    }

    /** The requirements no loaded module satisfies, as "name:version" (just "name" when no version is required). */
    static List<String> missingDependencyList(List<ModuleDescriptor.Requires> requires, List<ModuleDescriptor> loadedModules) {
        return requires.stream()
                .filter(r -> loadedModules.stream().noneMatch(m -> VersionUtil.moduleCompatible(m, r)))
                .map(r -> r.name() + r.compiledVersion().map(v -> ":" + v).orElse(""))
                .distinct() // several modules of the plugin may require the same one
                .toList();
    }

    static String missingDependencies(List<ModuleDescriptor.Requires> requires, List<ModuleDescriptor> loadedModules) {
        return String.join(", ", missingDependencyList(requires, loadedModules));
    }

    private void instanciateResolved() {
        instanciate(ModuleLayer.boot(), null); // the embedded plugin
        for (LayerLoader ll : loadedLayers) {
            instanciate(ll.getModuleLayer(), ll);
        }
    }

    /**
     * Instantiates the plugin providers of this layer only (a layer also sees those of its parents, registered
     * with their own layer). A provider that cannot be instantiated is an error of its plugin, the others load.
     */
    private void instanciate(ModuleLayer layer, LayerLoader ll) {
        try {
            var providers = ServiceLoader.load(layer, IPlugin.class).stream()
                    .filter(p -> p.type().getModule().getLayer() == layer)
                    .toList();
            for (ServiceLoader.Provider<IPlugin> provider : providers) {
                IPlugin service = provider.get();
                // load i18n
                service.setResourceBundle(ResourcesEngine.buildPluginResourceBundle(service.getI18nBundleNames(), service.getClass().getModule()));
                // register
                if (ll == null) {
                    if (service.getClass().equals(CBEmbeddedPlugin.class)) {
                        pluginDefinitions.add(PluginDefinition.ofEmbedded(service));
                    }
                } else {
                    pluginDefinitions.add(PluginDefinition.ofSuccess(ll, service));
                }
            }
        } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
            if (ll == null) {
                throw e; // the embedded plugin is part of the engine: nothing works without it
            }
            pluginDefinitions.add(PluginDefinition.ofError(ll, ResourcesEngine.getString("plugin.load.instantiation", describe(e))));
        }
    }

    /** The error and its root cause, the message a plugin author needs (e.g. the exception of a constructor). */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? String.valueOf(e) : e + " (" + root + ")";
    }
}
