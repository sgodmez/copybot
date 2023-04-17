package com.copybot.plugin.demo.inheritance;

import com.copybot.plugin.definition.api.IPlugin;
import com.copybot.plugin.demo.module.DemoTransformModuleModule;
import com.copybot.plugin.demo.module.old.DemoTransformModuleModuleOlder;
import com.drew.metadata.Metadata;

import java.io.File;

public class DemoTransformInhheritance implements IPlugin {

    @Override
    public String getName() {
        return "DemoPluginInhheritance";
    }

    @Override
    public void doManyThings(File file) {
        DemoTransformModuleModule.extract();
        DemoTransformModuleModuleOlder.extract();
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }

}