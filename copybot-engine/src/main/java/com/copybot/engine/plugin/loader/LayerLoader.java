package com.copybot.engine.plugin.loader;

import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;
import com.copybot.plugin.api.definition.IPlugin;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class LayerLoader {
    private Path path;
    private ModuleFinder pluginsFinder;
    private Set<ModuleDescriptor> moduleDescriptors;
    private ModuleDescriptor mainModuleDescriptor;
    private String error;
    private Configuration pluginConfiguration;
    private ModuleLayer moduleLayer;

    private List<ModuleDescriptor.Requires> requires;

    public static LayerLoader of(Path path) {
        return new LayerLoader(path, FileUtil.listDirectoryRecur(path, true));
    }

    public static LayerLoader ofDev(Path path) {
        return new LayerLoader(path, List.of(path.resolve("classes"), path.resolve("lib")));
    }

    private LayerLoader(Path path, List<Path> pluginDirRecur) {
        this.path = path;

        // Search for plugins in the plugins directory
        pluginsFinder = ModuleFinder.of(pluginDirRecur.toArray(new Path[0]));
        // Find all names of all found plugin modules
        Set<ModuleReference> plugins = pluginsFinder.findAll();

        moduleDescriptors = plugins.stream()
                .map(ModuleReference::descriptor)
                .collect(Collectors.toSet());

        List<ModuleDescriptor> mainModules = moduleDescriptors.stream()
                .filter(d -> d.provides().stream().anyMatch(p -> p.service().equals(IPlugin.class.getName())))
                .toList();

        if (mainModules.isEmpty()) {
            error = ResourcesEngine.getString("plugin.load.no-module", path);
            return;
        }
        if (mainModules.size() > 1) {
            error = ResourcesEngine.getString("plugin.load.many-modules", path,
                    mainModules.stream().map(ModuleDescriptor::name).sorted().collect(Collectors.joining(", ")));
            return;
        }
        // TODO vérifier la version de com.copybot.plugin.api

        mainModuleDescriptor = mainModules.get(0);

        requires = moduleDescriptors.stream()
                .flatMap(d -> d.requires().stream())
                .filter(r -> !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED))
                .collect(Collectors.toList());

        // remove requirement provided by main copybot app
        var rootModules = ModuleLayer.boot().modules().stream()
                .map(Module::getDescriptor)
                .toList();
        requires.removeIf(req -> rootModules.stream().anyMatch(m -> VersionUtil.moduleCompatible(m, req)));

        // remove internally provided requirements
        requires.removeIf(req -> moduleDescriptors.stream().anyMatch(m -> VersionUtil.moduleCompatible(m, req)));
    }

    public boolean canBeLoaded() {
        return requires.isEmpty();
    }

    public boolean canBeLoaded(List<ModuleLayer> parentLayers) {
        var parentModules = parentLayers.stream()
                .flatMap(l -> l.modules().stream())
                .map(Module::getDescriptor)
                .toList();
        return requires.stream().allMatch(req -> parentModules.stream().anyMatch(m -> VersionUtil.moduleCompatible(m, req)));
    }


    public void load() {
        load(List.of(ModuleLayer.boot()));
    }

    public void load(List<ModuleLayer> parentLayers) {
        List<String> pluginsName = moduleDescriptors
                .stream()
                .map(ModuleDescriptor::name)
                .collect(Collectors.toList());

        // Create configuration that will resolve plugin modules
        // (verify that the graph of modules is correct)
        var parentConfs = parentLayers.stream()
                .map(ModuleLayer::configuration)
                .toList();

        // create layer config
        pluginConfiguration = Configuration.resolve(pluginsFinder, parentConfs, ModuleFinder.of(), pluginsName);
        // create layer
        moduleLayer = ModuleLayer.defineModulesWithOneLoader(pluginConfiguration, parentLayers, ClassLoader.getSystemClassLoader()).layer();
    }

    public ModuleDescriptor getMainModuleDescriptor() {
        return mainModuleDescriptor;
    }

    public List<ModuleDescriptor.Requires> getRequires() {
        return requires;
    }

    public Path getPath() {
        return path;
    }

    public String getError() {
        return error;
    }

    public Configuration getPluginConfiguration() {
        return pluginConfiguration;
    }

    public ModuleLayer getModuleLayer() {
        return moduleLayer;
    }

    /** The version of the main module, null without main module (see {@link #getError()}) or without version. */
    public String getVersion() {
        if (mainModuleDescriptor == null) {
            return null;
        }
        return mainModuleDescriptor.version().map(Object::toString).orElse(null);
    }
}
