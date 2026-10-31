package com.copybot.engine.plugin.loader;

import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.logger.CopybotLogger;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class LayerLoader {
    private static final CopybotLogger LOG = CopybotLogger.getLogger(LayerLoader.class);
    /** Native access refused once is refused for every plugin: said once per JVM. */
    private static final AtomicBoolean NATIVE_ACCESS_WARNED = new AtomicBoolean();

    private Path path;
    private final boolean dev;
    private ModuleFinder pluginsFinder;
    private Set<ModuleReference> moduleReferences = Set.of();
    private Set<ModuleDescriptor> moduleDescriptors;
    private ModuleDescriptor mainModuleDescriptor;
    private String error;
    private String errorKey;
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
            fail("plugin.load.unreadable", path,
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
            fail("plugin.load.no-module", path);
            return;
        }
        if (mainModules.size() > 1) {
            fail("plugin.load.many-modules", path,
                    mainModules.stream().map(ModuleDescriptor::name).sorted().collect(Collectors.joining(", ")));
            return;
        }
        mainModuleDescriptor = mainModules.get(0);

        String engineVersion = VersionUtil.engineVersion().orElse(null);
        Optional<String> builtFor = incompatibleApi(moduleDescriptors, VersionUtil.engineModuleName(), engineVersion);
        if (builtFor.isPresent()) {
            // refused now, with the reason, rather than a LinkageError when an action runs
            fail("plugin.load.api-incompatible", builtFor.get(), engineVersion);
            return;
        }

        // "requires static": needed at compile time only, optional at run time (JPMS): never missing
        // (commons-compress of gps-zone, whose xz, asm, brotli codecs are not shipped)
        requires = moduleDescriptors.stream()
                .flatMap(d -> d.requires().stream())
                .filter(r -> !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED))
                .filter(r -> !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC))
                .collect(Collectors.toList());

        // remove requirement provided by main copybot app
        var rootModules = ModuleLayer.boot().modules().stream()
                .map(Module::getDescriptor)
                .toList();
        requires.removeIf(req -> rootModules.stream().anyMatch(m -> VersionUtil.moduleCompatible(m, req)));

        // remove internally provided requirements
        requires.removeIf(req -> moduleDescriptors.stream().anyMatch(m -> VersionUtil.moduleCompatible(m, req)));
    }

    /**
     * The plugin API version a module of the plugin was built against, when this engine cannot run it. A plugin
     * declares it without doing anything: javac records the version of the engine module it compiled against in
     * its {@code requires com.copybot.engine} (the engine module holds the plugin API, its version is the API
     * version, distinct from the application version). Compatible: the same major version, a minor version at most
     * the engine's
     * ({@link VersionUtil#isCompatible}).
     *
     * @param engineModule  the name of the engine module, null when the engine runs from the class path
     * @param engineVersion the version of the engine module (the plugin API version), null when it has none
     * @return empty when compatible, or when a version is unknown (nothing recorded, engine without version)
     */
    static Optional<String> incompatibleApi(Collection<ModuleDescriptor> modules, String engineModule, String engineVersion) {
        if (engineModule == null || engineVersion == null) {
            return Optional.empty();
        }
        return modules.stream()
                .flatMap(d -> d.requires().stream())
                .filter(r -> r.name().equals(engineModule))
                .flatMap(r -> r.compiledVersion().stream())
                .map(Object::toString)
                .filter(builtFor -> !VersionUtil.isCompatible(engineVersion, builtFor, false))
                .findFirst();
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
        ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(pluginConfiguration, parentLayers, ClassLoader.getSystemClassLoader());
        moduleLayer = controller.layer();
        enableNativeAccess(controller);
    }

    /**
     * Grants native access to the modules of the layer: --enable-native-access cannot name a module of a plugin
     * layer (zstd-jni of gps-zone loads its library). The engine must have it itself (the launchers give it
     * --enable-native-access=com.copybot.engine): without, the JVM warns once and grants it, and refuses it under
     * --illegal-native-access=deny (logged once, the plugin still loads; its native calls are then refused).
     */
    private static void enableNativeAccess(ModuleLayer.Controller controller) {
        for (Module module : controller.layer().modules()) {
            try {
                controller.enableNativeAccess(module);
            } catch (RuntimeException e) { // IllegalCallerException: the engine has no native access
                if (NATIVE_ACCESS_WARNED.compareAndSet(false, true)) {
                    LOG.warn("plugin.load.native-access", module.getName(), String.valueOf(e));
                }
                return;
            }
        }
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

    /** The bundle key (plugin.load.*) of {@link #getError()}, which is translated once; null without error. */
    public String getErrorKey() {
        return errorKey;
    }

    private void fail(String key, Object... args) {
        errorKey = key;
        error = ResourcesEngine.getString(key, args);
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
