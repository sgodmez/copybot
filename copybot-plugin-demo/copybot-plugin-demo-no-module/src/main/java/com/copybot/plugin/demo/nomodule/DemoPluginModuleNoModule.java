package com.copybot.plugin.demo.nomodule;

import com.copybot.plugin.api.ICBPluginModule;
import com.drew.metadata.Metadata;
import com.google.auto.service.AutoService;

import java.io.File;

@AutoService(ICBPluginModule.class)
public class DemoPluginModuleNoModule implements ICBPluginModule {

    @Override
    public String getName() {
        return "DemoServiceNoModule";
    }

    @Override
    public void doManyThings(File file) {
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }
}