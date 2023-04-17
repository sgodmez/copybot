package com.copybot.plugin.demo.module;

import com.copybot.plugin.definition.api.IPlugin;
import com.drew.metadata.Metadata;

import java.io.File;

public class DemoTransformModuleModule implements IPlugin {

    @Override
    public String getName() {
        return "DemoServiceModule";
    }

    @Override
    public void doManyThings(File file) {
        extract();
    }

    public static void extract() {
        System.out.println("Resolving metadata with : " + Metadata.class.getProtectionDomain().getCodeSource().getLocation());
    }


}