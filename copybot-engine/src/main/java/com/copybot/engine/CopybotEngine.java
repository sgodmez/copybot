package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.utils.FileUtil;
import com.copybot.plugin.definition.IPlugin;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CopybotEngine {
    private static IPlugin m;

    public static ResourceBundle resourceBundle = ResourceBundle.getBundle("com.copybot.engine.engineBundle");


    public static void test() {
        PluginEngine.load(Path.of("D:\\plugins\\"));
        List<Path> pluginsDirs = FileUtil.listDirectory(Path.of("D:\\plugins\\")); // Directory with plugins JARs

        var gson = new GsonBuilder().setPrettyPrinting().create();

/*
        var config = new PipelineConfig(List.of(new PipelineStep("test",null)),List.of(),List.of(),List.of());
        var result = gson.toJson(config);
        System.out.println(result);
*/
        PipelineConfig c = gson.fromJson("""
                {
                  "inSteps": [
                    {
                      "action": "test",
                      "actionConfig" : {
                         "path" : "d:/photos/"
                      }
                    }
                  ],
                  "analyseSteps": [],
                  "outSteps": []
                }
                """, PipelineConfig.class);
        //Test t = gson.fromJson(c.inSteps().get(0).actionConfig(),Test.class);
        var cl = Thread.currentThread().getContextClassLoader();

        m = PluginEngine.getPluginDefinitions().get(1).getPluginInstance();
        var cl2 = m.getClass();
        var actionClass = m.getInActions().get(0);
        try {
            var actionInstance = actionClass.getConstructor().newInstance();
            actionInstance.loadConfig(c.inSteps().get(0).actionConfig());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static Configuration getConfiguration(Path pluginDir, ModuleLayer layer) {
        List<Path> pluginDirRecur = FileUtil.listDirectoryRecur(pluginDir, true);

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
        List<Path> pluginDirRecur = FileUtil.listDirectoryRecur(pluginDir, true);

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
