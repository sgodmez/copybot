package com.copybot.engine.plugin;

import com.copybot.engine.plugin.loader.LayerLoader;
import com.copybot.plugin.definition.IPlugin;

import java.nio.file.Path;

public class PluginDefinition {
    private String name;
    private String version;
    private Path path;
    private boolean active;
    private String errorMessage;
    private IPlugin pluginInstance;

    public static PluginDefinition ofError(LayerLoader ll) {
        return ofError(ll, null);
    }

    public static PluginDefinition ofError(LayerLoader ll, String errorMessage) {
        var name = ll.getMainModuleDescriptor().name();
        var version = ll.getVersion();
        var path = ll.getPath();
        var errorMessageResolved = errorMessage != null ? errorMessage : ll.getError();
        return new PluginDefinition(name, version, path, false, errorMessage, null);
    }

    public static PluginDefinition ofSuccess(LayerLoader ll, IPlugin pluginInstance) {
        var name = ll.getMainModuleDescriptor().name();
        var version = ll.getVersion();
        var path = ll.getPath();
        return new PluginDefinition(name, version, path, true, null, pluginInstance);
    }

    public static PluginDefinition ofEmbedded(IPlugin pluginInstance) {
        return new PluginDefinition("Embedded", null, null, true, null, pluginInstance);
    }

    private PluginDefinition(String name, String version, Path path, boolean active, String errorMessage, IPlugin pluginInstance) {
        this.name = name;
        this.version = version;
        this.path = path;
        this.active = active;
        this.errorMessage = errorMessage;
        this.pluginInstance = pluginInstance;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public Path getPath() {
        return path;
    }

    public boolean isActive() {
        return active;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public IPlugin getPluginInstance() {
        return pluginInstance;
    }
}
