package com.copybot.plugin.embedded;

import com.copybot.plugin.definition.api.IPlugin;

import java.io.File;

public class CBEmbeddedPlugin implements IPlugin {

    @Override
    public String getName() {
        return "dummy";
    }

    @Override
    public void doManyThings(File file) {

    }
}