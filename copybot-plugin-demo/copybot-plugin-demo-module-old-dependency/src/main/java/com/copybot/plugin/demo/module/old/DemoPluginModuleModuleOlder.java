package com.copybot.plugin.demo.module.old;

import com.copybot.plugin.api.ICBPluginModule;
import com.drew.metadata.Metadata;

import java.io.File;

public class DemoPluginModuleModuleOlder implements ICBPluginModule {

    @Override
    public String getName() {
        return "DemoServiceModuleOlder";
    }

    @Override
    public void doManyThings(File file) {
        extract();
    }

    public static void extract() {
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }


}