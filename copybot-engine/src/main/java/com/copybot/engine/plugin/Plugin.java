package com.copybot.engine.plugin;

import com.copybot.plugin.action.IProcessAction;

import java.nio.file.Path;
import java.util.List;

public class Plugin {

    private final PluginDeclaration declaration;
    private final Path dir;
    private final Path jar;
    private final List<IProcessAction> modules;

    public Plugin(PluginDeclaration declaration, Path dir, Path jar, List<IProcessAction> modules) {
        this.declaration = declaration;
        this.dir = dir;
        this.jar = jar;
        this.modules = modules;
    }
}
