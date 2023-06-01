package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.utils.FileUtil;
import com.copybot.plugin.definition.IPlugin;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class CopybotEngine {
    private static IPlugin m;

    public static ResourceBundle resourceBundle = ResourceBundle.getBundle("com.copybot.engine.engineBundle");

    private static PluginEngine pluginEngine;

    public static void init() {
        //pluginEngine
    }

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
/*
        m = PluginEngine.getLoadedPlugins().get(1).getPluginInstance();
        var cl2 = m.getClass();
        var actionClass = m.getInActions().get(0);
        try {
            var actionInstance = actionClass.getConstructor().newInstance();
            actionInstance.loadConfig(c.inSteps().get(0).actionConfig());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
 */
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
