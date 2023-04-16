package com.copybot.plugin.demo.inheritance;

import com.copybot.plugin.api.ICBPluginModule;
import com.copybot.plugin.demo.module.DemoPluginModuleModule;
import com.copybot.plugin.demo.module.old.DemoPluginModuleModuleOlder;
import com.drew.metadata.Metadata;

import java.io.File;

public class DemoPluginInhheritance implements ICBPluginModule {

    @Override
    public String getName() {
        return "DemoPluginInhheritance";
    }

    @Override
    public void doManyThings(File file) {
        DemoPluginModuleModule.extract();
        DemoPluginModuleModuleOlder.extract();
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }

}