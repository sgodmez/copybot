package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.FileUtil;
import com.copybot.utils.VersionUtil;
import com.copybot.plugin.api.definition.IPlugin;

import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class LayerLoader {
    private Path path;
    private final boolean dev;
    private ModuleFinder pluginsFinder;
    private Set<ModuleReference> moduleReferences = Set.of();
    private Set<ModuleDescriptor> moduleDescriptors;
    private ModuleDescriptor mainModuleDescriptor;
    private String error;
    private Configuration pluginConfiguration;
    private ModuleLayer moduleLayer;
    private List<LayerLoader> parentLoaders = List.of();

    private List<ModuleDescriptor.Requires> requires;

    public static LayerLoader of(Path path) {
        return new LayerLoader(path, FileUtil.listDirectoryRecur(path, true), false);
    }

    public static LayerLoader ofDev(Path path) {
        return new LayerLoader(path, List.of(path.resolve("classes"), path.resolve("lib")), true);
    }

    private LayerLoader(Path path, List<Path> pluginDirRecur, boolean dev) {
        this.path = path;
        this.dev = dev;

        // Search for plugins in the plugins directory
        pluginsFinder = ModuleFinder.of(pluginDirRecur.toArray(new Path[0]));
        // Find all names of all found plugin modules
        try {
            moduleReferences = pluginsFinder.findAll();
        } catch (FindException e) { // an unreadable jar, two modules of the same name...
            error = ResourcesEngine.getString("plugin.load.unreadable", path,
                    e.getCause() != null ? e.getMessage() + " (" + e.getCause() + ")" : e.getMessage());
            return;
        }

        moduleDescriptors = moduleReferences.stream()
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


    /** Loads the layer over the layers of these already loaded plugins, or over the boot layer without any. */
    public void load(List<LayerLoader> parents) {
        List<ModuleLayer> parentLayers = parents.isEmpty()
                ? List.of(ModuleLayer.boot())
                : parents.stream().map(LayerLoader::getModuleLayer).toList();
        List<String> pluginsName = moduleDescriptors
                .stream()
                .map(ModuleDescriptor::name)
                .collect(Collectors.toList());

        // Create configuration that will resolve plugin modules
        // (verify that the graph of modules is correct)
        var parentConfs = parentLayers.stream()
                .map(ModuleLayer::configuration)
                .toList();

        parentLoaders = List.copyOf(parents); // before resolution: an error of this plugin still lists them
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

    /** True for a development directory (classes/ and lib/ sub-directories). */
    public boolean isDev() {
        return dev;
    }

    public String getError() {
        return error;
    }

    public ModuleLayer getModuleLayer() {
        return moduleLayer;
    }

    /** The plugins whose layers this one was loaded over (empty without plugin dependency). */
    public List<LayerLoader> getParentLoaders() {
        return parentLoaders;
    }

    /** The version of the main module, null without main module (see {@link #getError()}) or without version. */
    public String getVersion() {
        if (mainModuleDescriptor == null) {
            return null;
        }
        return mainModuleDescriptor.version().map(Object::toString).orElse(null);
    }

    /** The modules of the directory, main module first then by name; empty when it could not be read. */
    public List<ModuleEntry> getModuleEntries() {
        return moduleReferences.stream()
                .map(ref -> new ModuleEntry(ref.descriptor().name(),
                        ref.descriptor().version().map(Object::toString).orElse(null),
                        ref.location().map(Path::of).map(p -> p.toAbsolutePath().normalize()).orElse(null),
                        mainModuleDescriptor != null && ref.descriptor().name().equals(mainModuleDescriptor.name()),
                        ref.descriptor().isAutomatic()))
                .sorted(Comparator.comparing(ModuleEntry::main).reversed().thenComparing(ModuleEntry::name))
                .toList();
    }
}
