package com.copybot.plugin.embedded;

import com.copybot.plugin.api.ICBPluginModule;

import java.io.File;

public class DummyPluginModule implements ICBPluginModule {

    @Override
    public String getName() {
        return "dummy";
    }

    @Override
    public void doManyThings(File file) {

    }
}