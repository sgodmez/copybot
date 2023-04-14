package com.copybot.engine;

import com.copybot.plugin.MyService;

import java.io.File;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CopybotEngine {


    public static MyService m;

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

        ModuleLayer layer = ModuleLayer.boot();
        for (Path pluginDir : pluginsDirs) {
            Configuration pluginsConfiguration = getConfiguration(pluginDir);
            // Create a module layer for plugin dir
            layer = layer.defineModulesWithOneLoader(pluginsConfiguration, ClassLoader.getSystemClassLoader());
        }

        ServiceLoader<MyService> serviceLoader = ServiceLoader.load(layer, MyService.class);

        Map<String, MyService> services = new HashMap<>();
        for (MyService service : serviceLoader) {
            System.out.println("I've found a service called '" + service.getName() + "' !");
            services.put(service.getName(), service);
            m = service;
        }

        System.out.println("Found " + services.size() + " services!");
    }

    private static Configuration getConfiguration(Path pluginDir) {
        List<Path> pluginDirRecur = listDirectoryRecur(Path.of("D:\\plugins"), true);

        // Search for plugins in the plugins directory
        ModuleFinder pluginsFinder = ModuleFinder.of(pluginDirRecur.toArray(new Path[0]));

        // Find all names of all found plugin modules
        List<String> plugins = pluginsFinder
                .findAll()
                .stream()
                .map(ModuleReference::descriptor)
                .map(ModuleDescriptor::name)
                .collect(Collectors.toList());

        // Create configuration that will resolve plugin modules
        // (verify that the graph of modules is correct)
        Configuration pluginsConfiguration = ModuleLayer
                .boot()
                .configuration()
                .resolve(pluginsFinder, ModuleFinder.of(), plugins);
        return pluginsConfiguration;
    }
}
