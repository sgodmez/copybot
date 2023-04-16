package com.copybot.engine;

import com.copybot.plugin.api.ICBPluginModule;

import java.io.File;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CopybotEngine {
    private static ICBPluginModule m;

    private static List<Path> listDirectory(Path dirPath) {
        List<Path> paths = new ArrayList<>();
        doListDirectory(dirPath, paths, false);
        return paths;
    }

    private static List<Path> listDirectoryRecur(Path dirPath, boolean withRoot) {
        List<Path> paths = new ArrayList<>();
        if (withRoot) {
            paths.add(dirPath);
        }
        doListDirectory(dirPath, paths, true);
        return paths;
    }

    private static void doListDirectory(Path dirPath, List<Path> pathList, boolean recur) {
        File[] files = dirPath.toFile().listFiles();
        if (files != null) {
            for (File aFile : files) {
                if (aFile.isDirectory()) {
                    Path aFilePath = aFile.toPath();
                    pathList.add(aFilePath);
                    if (recur) {
                        doListDirectory(aFilePath, pathList, recur);
                    }
                }
            }
        }
    }

    public static void test() {
        List<Path> pluginsDirs = listDirectory(Path.of("D:\\plugins")); // Directory with plugins JARs
/*
        for (Path pluginDir : pluginsDirs) {
            Configuration pluginsConfiguration = getConfiguration(pluginDir, ModuleLayer.boot());
            // Create a module layer for plugin dir
            ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(pluginsConfiguration, ClassLoader.getSystemClassLoader());

            ServiceLoader<MyService> serviceLoader = ServiceLoader.load(layer, MyService.class);

            Map<String, MyService> services = new HashMap<>();
            for (MyService service : serviceLoader) {
                System.out.println("I've found a service called '" + service.getName() + "' !");
                services.put(service.getName(), service);
                m = service;
            }

            System.out.println("Found " + services.size() + " services!");
        }
*/
        /*
        List<ModuleLayer> layers = new ArrayList<>();
        layers.add(ModuleLayer.boot());
        for (Path pluginDir : pluginsDirs) {
            Configuration pluginsConfiguration = getConfiguration(pluginDir, layers);
            // Create a module layer for plugin dir
            var layer = ModuleLayer.defineModulesWithOneLoader(pluginsConfiguration, layers, ClassLoader.getSystemClassLoader()).layer();
            //layer = layer.defineModulesWithOneLoader(pluginsConfiguration, ClassLoader.getSystemClassLoader());
            layers.add(layer);


            ServiceLoader<MyService> serviceLoader = ServiceLoader.load(layer, MyService.class);

            Map<String, MyService> services = new HashMap<>();
            for (MyService service : serviceLoader) {
                System.out.println("I've found a service called '" + service.getName() + "' !");
                services.put(service.getName(), service);
                m = service;
            }

            System.out.println("Found " + services.size() + " services!");
        }
         */
        var r1 = pluginsDirs.get(0);
        Configuration c1 = getConfiguration(r1, ModuleLayer.boot());
        // Create a module layer for plugin dir
        ModuleLayer l1 = ModuleLayer.boot().defineModulesWithOneLoader(c1, ClassLoader.getSystemClassLoader());

        var r2 = pluginsDirs.get(1);
        Configuration c2 = getConfiguration(r2, ModuleLayer.boot());
        // Create a module layer for plugin dir
        ModuleLayer l2 = ModuleLayer.defineModulesWithOneLoader(c2, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();

        var r3 = pluginsDirs.get(2);
        List<ModuleLayer> layers3 = List.of(l1, l2);
        Configuration c3 = getConfiguration(r3, layers3);
        ModuleLayer l3 = ModuleLayer.defineModulesWithOneLoader(c3, layers3, ClassLoader.getSystemClassLoader()).layer();

        var r4 = pluginsDirs.get(3);
        Configuration c4 = getConfiguration(r4, ModuleLayer.boot());
        // Create a module layer for plugin dir
        ModuleLayer l4 = ModuleLayer.boot().defineModulesWithOneLoader(c4, ClassLoader.getSystemClassLoader());

        Configuration c99 = Configuration.resolve(ModuleFinder.ofSystem(), List.of(c1, c2, c3, c4), ModuleFinder.of(), List.of());
        ModuleLayer l99 = ModuleLayer.defineModulesWithOneLoader(c99, List.of(l1, l2, l3, l4), ClassLoader.getSystemClassLoader()).layer();

        ServiceLoader<ICBPluginModule> serviceLoader = ServiceLoader.load(l99, ICBPluginModule.class);

        Map<String, ICBPluginModule> services = new HashMap<>();
        for (ICBPluginModule service : serviceLoader) {
            System.out.println("I've found a service called '" + service.getName() + "' ! " + service.getClass().getModule().getName() + " : " + service.getClass().getProtectionDomain().getCodeSource().getLocation());
            service.doManyThings(null);
            services.put(service.getName(), service);
            m = service;
        }


    }

    private static Configuration getConfiguration(Path pluginDir, ModuleLayer layer) {
        List<Path> pluginDirRecur = listDirectoryRecur(pluginDir, true);

        // Search for plugins in the plugins directory
        ModuleFinder pluginsFinder = ModuleFinder.of(pluginDirRecur.toArray(new Path[0]));

        // Find all names of all found plugin modules
        Set<ModuleReference> plugins = pluginsFinder
                .findAll();

/*
        List<String> loadedModules = layer.modules().stream()
                .map(Module::getName)
                .collect(Collectors.toList());

        plugins.stream()
                .map(m -> m.descriptor().name())
                .forEach(loadedModules::add);

        List<String> notResolved = plugins.stream()
                .flatMap(m -> m.descriptor().requires().stream())
                .map(ModuleDescriptor.Requires::name)
                .filter(Predicate.not(loadedModules::contains))
                .distinct()
                .toList();
*/

        List<String> pluginsName = plugins
                .stream()
                .map(ModuleReference::descriptor)
                .map(ModuleDescriptor::name)
                .collect(Collectors.toList());

        // Create configuration that will resolve plugin modules
        // (verify that the graph of modules is correct)
        Configuration pluginsConfiguration = layer
                .configuration()
                .resolve(pluginsFinder, ModuleFinder.of(), pluginsName);
        return pluginsConfiguration;
    }

    private static Configuration getConfiguration(Path pluginDir, List<ModuleLayer> layer) {
        List<Path> pluginDirRecur = listDirectoryRecur(pluginDir, true);

        // Search for plugins in the plugins directory
        ModuleFinder pluginsFinder = ModuleFinder.of(pluginDirRecur.toArray(new Path[0]));

        // Find all names of all found plugin modules
        Set<ModuleReference> plugins = pluginsFinder
                .findAll();


        List<String> pluginsName = plugins
                .stream()
                .map(ModuleReference::descriptor)
                .map(ModuleDescriptor::name)
                .collect(Collectors.toList());

        // Create configuration that will resolve plugin modules
        // (verify that the graph of modules is correct)
        var confs = layer.stream()
                .map(ModuleLayer::configuration)
                .toList();
        Configuration pluginsConfiguration = Configuration.resolve(pluginsFinder, confs, ModuleFinder.of(), pluginsName);
        return pluginsConfiguration;
    }

    public static void run(Consumer<Path> pConsumer) {
        try {
            Files.find(Path.of("D:\\photos"),
                            Integer.MAX_VALUE,
                            (filePath, fileAttr) -> fileAttr.isRegularFile())
                    .forEach(p -> {
//                            try {
//                                Thread.currentThread().sleep(3);
//                            } catch (InterruptedException e) {
//                                throw new RuntimeException(e);
//                            }
/*
                            try {
                                extractMetadata(p.toFile());
                            } catch (ImageProcessingException e) {
                                //throw new RuntimeException(e);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
*/
                        CopybotEngine.m.doManyThings(p.toFile());

                        pConsumer.accept(p);
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
