package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.VersionUtil;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                ModuleDescriptor.Version curentVerion = ll.getMainModuleDescriptor().version().get();
                ModuleDescriptor.Version previousVersion = previousLl.getMainModuleDescriptor().version().get();
                if (curentVerion.compareTo(previousVersion) > 0) {
                    // new ll is a more recent revision version of this minor version
                    validLayers.remove(previousLl);
                    pluginDefinitions.add(PluginDefinition.ofError(previousLl, "révision plus récente trouvée")); // todo msg
                } else if (curentVerion.equals(previousVersion)) {
                    pluginDefinitions.add(PluginDefinition.ofError(ll, "déjà chargé")); // todo msg
                    continue;
                } else {
                    pluginDefinitions.add(PluginDefinition.ofError(ll, "révision plus récente trouvée")); // todo msg
                    continue;
                }
            }
            validLayers.add(ll);
        }
    }

    private void loadNoDepPlugins() {
        for (LayerLoader ll : validLayers) {
            if (ll.canBeLoaded()) { // no plugin dependency
                ll.load();
                loadedLayers.add(ll);
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
                List<ModuleLayer> candiadateLayers = getCandidates(ll);

                if (ll.canBeLoaded(candiadateLayers)) {
                    ll.load(candiadateLayers);
                    loadedLayers.add(ll);
                    hasResolvedPlugin = true;
                    it.remove();
                }
            }
        } while (hasResolvedPlugin);

        // can't resolve theses
        markUnresolvedPlugin();
    }

    private List<ModuleLayer> getCandidates(LayerLoader ll) {
        var candiadateLayers = loadedLayers.stream()
                .filter(c -> ll.getRequires().stream().anyMatch(r -> VersionUtil.moduleCompatible(c.getMainModuleDescriptor(), r)))
                .map(LayerLoader::getModuleLayer)
                .toList();
        return candiadateLayers;
    }

    private void markUnresolvedPlugin() {
        for (LayerLoader ll : layersWithDependencies) {
            String missingDependencies = ll.getRequires().stream()
                    .filter(r -> r.compiledVersion().isPresent())
                    .filter(r -> loadedLayers.stream().anyMatch(m -> VersionUtil.moduleCompatible(m.getMainModuleDescriptor(), r)))
                    .map(r -> r.name() + ":" + r.compiledVersion().get().toString())
                    .collect(Collectors.joining(", "));

            pluginDefinitions.add(PluginDefinition.ofError(ll, "Missing dependencies : '" + missingDependencies + "'")); // todo msg
        }
    }

    private void instanciateResolved() {
        Map<ModuleLayer, LayerLoader> moduleLayerToLayerLoaderMap;
        ModuleLayer allLayers;
        if (loadedLayers.isEmpty()) {
            // no plugins
            moduleLayerToLayerLoaderMap = Map.of();
            allLayers = ModuleLayer.boot();
        } else {
            moduleLayerToLayerLoaderMap = loadedLayers.stream().collect(Collectors.toMap(LayerLoader::getModuleLayer, Function.identity()));
            Configuration allConfig = Configuration.resolve(ModuleFinder.ofSystem(), loadedLayers.stream().map(LayerLoader::getPluginConfiguration).toList(), ModuleFinder.of(), List.of());
            allLayers = ModuleLayer.defineModulesWithOneLoader(allConfig, loadedLayers.stream().map(LayerLoader::getModuleLayer).toList(), ClassLoader.getSystemClassLoader()).layer();
        }


        ServiceLoader<IPlugin> serviceLoader = ServiceLoader.load(allLayers, IPlugin.class);

        for (IPlugin service : serviceLoader) {
            // load i18n
            service.setResourceBundle(ResourcesEngine.buildPluginResourceBundle(service.getI18nBundleNames(), service));
            // register
            if (service.getClass().equals(CBEmbeddedPlugin.class)) {
                pluginDefinitions.add(PluginDefinition.ofEmbedded(service));
            } else {
                LayerLoader ll = moduleLayerToLayerLoaderMap.get(service.getClass().getModule().getLayer());
                System.out.println("I've found a service called '" + service.getPluginCode() + "' ! " + ll.getPath());
                pluginDefinitions.add(PluginDefinition.ofSuccess(ll, service));
            }
        }
    }
}
