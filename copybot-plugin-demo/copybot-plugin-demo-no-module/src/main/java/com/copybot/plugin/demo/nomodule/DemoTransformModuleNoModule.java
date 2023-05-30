package com.copybot.plugin.demo.nomodule;

import com.copybot.plugin.definition.IPlugin;
import com.drew.metadata.Metadata;
import com.google.auto.service.AutoService;

import java.io.File;

@AutoService(IPlugin.class)
public class DemoTransformModuleNoModule implements IPlugin {

    @Override
    public String getName() {
        return "DemoServiceNoModule";
    }

    @Override
    public void doManyThings(File file) {
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }
}