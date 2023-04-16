package com.copybot.plugin.api;

import java.io.File;

public interface ICBPluginModule {

    String getName();

    void doManyThings(File file);
}