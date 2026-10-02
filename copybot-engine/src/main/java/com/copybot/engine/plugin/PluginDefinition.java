package com.copybot.engine.plugin;

import com.copybot.exception.ActionNotFoundException;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.LayerLoader;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

import java.nio.file.Path;

public final class PluginDefinition {
    private String name;
    private String version;
    private Path path;
    private boolean active;
    private String errorMessage;
    private IPlugin pluginInstance;

    public static PluginDefinition ofError(LayerLoader ll) {
        return ofError(ll, null);
    }

    /**
     * A plugin that is not loaded, with this message (the loader error when null). Without main module (the
     * loader found none or several), it is named after its directory.
     */
    public static PluginDefinition ofError(LayerLoader ll, String errorMessage) {
        var path = ll.getPath();
        var name = ll.getMainModuleDescriptor() != null ? ll.getMainModuleDescriptor().name() : directoryName(path);
        var version = ll.getVersion();
        var errorMessageResolved = errorMessage != null ? errorMessage : ll.getError();
        return new PluginDefinition(name, version, path, false, errorMessageResolved, null);
    }

    private static String directoryName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    public static PluginDefinition ofSuccess(LayerLoader ll, IPlugin pluginInstance) {
        var name = ll.getMainModuleDescriptor().name();
        var version = ll.getVersion();
        var path = ll.getPath();
        return new PluginDefinition(name, version, path, true, null, pluginInstance);
    }

    public static PluginDefinition ofEmbedded(IPlugin pluginInstance) {
        return new PluginDefinition(CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME, null, null, true, null, pluginInstance);
    }

    // visible for tests: a loaded plugin without module layer
    static PluginDefinition ofLoaded(String name, String version, IPlugin pluginInstance) {
        return new PluginDefinition(name, version, null, true, null, pluginInstance);
    }

    private PluginDefinition(String name, String version, Path path, boolean active, String errorMessage, IPlugin pluginInstance) {
        this.name = name;
        this.version = version;
        this.path = path;
        this.active = active;
        this.errorMessage = errorMessage;
        this.pluginInstance = pluginInstance;
    }

    public ActionDefinition findAction(String name, StepType type) throws ActionNotFoundException {
        var actionList = switch (type) {
            case IN -> pluginInstance.getInActions();
            case ANALYZE -> pluginInstance.getAnalyzeActions();
            case PROCESS -> pluginInstance.getProcessActions();
            case OUT -> pluginInstance.getOutActions();
        };
        return actionList.stream()
                .filter(a -> a.actionCode().equals(name))
                .findFirst()
                .orElseThrow(() -> new ActionNotFoundException(name));
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
