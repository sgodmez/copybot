package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.utils.ModuleUtil;
import com.copybot.plugin.definition.IPlugin;
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

public class PluginLoader {
    List<LayerLoader> validLayers;
    List<LayerLoader> loadedLayers;
    List<LayerLoader> layersWithDepenencies;
    private List<PluginDefinition> pluginDefinitions;

    public PluginLoader() {
        validLayers = new ArrayList<>();
        pluginDefinitions = new ArrayList<>();
        loadedLayers = new ArrayList<>();
        layersWithDepenencies = new ArrayList<>();
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
                    .filter(previousLl -> ModuleUtil.moduleMatches(ll.getMainModuleDescriptor(), previousLl.getMainModuleDescriptor()))
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
                layersWithDepenencies.add(ll);
            }
        }
    }

    private void resolvePluginWithDependencies() {
        boolean hasResolvedPlugin;
        do {
            hasResolvedPlugin = false;
            var it = layersWithDepenencies.iterator();
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
                .filter(c -> ll.getRequires().stream().anyMatch(r -> ModuleUtil.moduleMatches(r, c.getMainModuleDescriptor())))
                .map(LayerLoader::getModuleLayer)
                .toList();
        return candiadateLayers;
    }

    private void markUnresolvedPlugin() {
        for (LayerLoader ll : layersWithDepenencies) {
            String missingDependencies = ll.getRequires().stream()
                    .filter(r -> r.compiledVersion().isPresent())
                    .filter(r -> loadedLayers.stream().anyMatch(m -> ModuleUtil.moduleMatches(r, m.getMainModuleDescriptor())))
                    .map(r -> r.name() + ":" + r.compiledVersion().get().toString())
                    .collect(Collectors.joining(", "));

            pluginDefinitions.add(PluginDefinition.ofError(ll, "Missing dependencies : '" + missingDependencies + "'")); // todo msg
        }
    }

    private void instanciateResolved() {
        Map<ModuleLayer, LayerLoader> moduleLayerToLayerLoaderMap = loadedLayers.stream().collect(Collectors.toMap(LayerLoader::getModuleLayer, Function.identity()));
        Configuration allConfig = Configuration.resolve(ModuleFinder.ofSystem(), loadedLayers.stream().map(LayerLoader::getPluginConfiguration).toList(), ModuleFinder.of(), List.of());
        ModuleLayer allLayers = ModuleLayer.defineModulesWithOneLoader(allConfig, loadedLayers.stream().map(LayerLoader::getModuleLayer).toList(), ClassLoader.getSystemClassLoader()).layer();

        ServiceLoader<IPlugin> serviceLoader = ServiceLoader.load(allLayers, IPlugin.class);

        for (IPlugin service : serviceLoader) {
            if (service.getClass().equals(CBEmbeddedPlugin.class)) {
                pluginDefinitions.add(PluginDefinition.ofEmbedded(service));
            } else {
                LayerLoader ll = moduleLayerToLayerLoaderMap.get(service.getClass().getModule().getLayer());
                System.out.println("I've found a service called '" + service.getName() + "' ! " + ll.getPath());
                pluginDefinitions.add(PluginDefinition.ofSuccess(ll, service));
            }
        }
    }
}