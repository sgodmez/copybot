package com.copybot.engine.plugin;

import com.copybot.exception.ActionNotFoundException;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.loader.LayerLoader;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;

import java.nio.file.Path;
import java.util.List;

public final class PluginDefinition {
    private String name;
    private String version;
    private Path path;
    private boolean active;
    private String errorMessage;
    private IPlugin pluginInstance;
    private PluginSource source;
    private boolean ignored;
    private List<ModuleEntry> modules = List.of();
    private List<String> pluginDependencies = List.of();
    private List<String> missingRequires = List.of();

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
        var definition = new PluginDefinition(name, version, path, false, errorMessageResolved, null, sourceOf(ll));
        definition.modules = ll.getModuleEntries();
        definition.pluginDependencies = dependenciesOf(ll);
        return definition;
    }

    /** A plugin set aside, not loaded because another one (a newer revision, or the same) is: it is no failure. */
    public static PluginDefinition ofIgnored(LayerLoader ll, String message) {
        var definition = ofError(ll, message);
        definition.ignored = true;
        return definition;
    }

    private static String directoryName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    private static PluginSource sourceOf(LayerLoader ll) {
        return ll.isDev() ? PluginSource.DEV : PluginSource.PLUGIN_PATH;
    }

    public static PluginDefinition ofSuccess(LayerLoader ll, IPlugin pluginInstance) {
        var name = ll.getMainModuleDescriptor().name();
        var version = ll.getVersion();
        var path = ll.getPath();
        var definition = new PluginDefinition(name, version, path, true, null, pluginInstance, sourceOf(ll));
        definition.modules = ll.getModuleEntries();
        definition.pluginDependencies = dependenciesOf(ll);
        return definition;
    }

    private static List<String> dependenciesOf(LayerLoader ll) {
        return ll.getParentLoaders().stream().map(PluginDefinition::describe).toList();
    }

    /** "name version", or just "name" without version. */
    private static String describe(LayerLoader ll) {
        String version = ll.getVersion();
        return ll.getMainModuleDescriptor().name() + (version != null ? " " + version : "");
    }

    public static PluginDefinition ofEmbedded(IPlugin pluginInstance) {
        return new PluginDefinition(CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME, null, null, true, null, pluginInstance, PluginSource.EMBEDDED);
    }

    // visible for tests: a loaded plugin without module layer
    static PluginDefinition ofLoaded(String name, String version, IPlugin pluginInstance) {
        return new PluginDefinition(name, version, null, true, null, pluginInstance, PluginSource.PLUGIN_PATH);
    }

    private PluginDefinition(String name, String version, Path path, boolean active, String errorMessage, IPlugin pluginInstance,
                             PluginSource source) {
        this.name = name;
        this.version = version;
        this.path = path;
        this.active = active;
        this.errorMessage = errorMessage;
        this.pluginInstance = pluginInstance;
        this.source = source;
    }

    /** Records the requirements no loaded plugin satisfied ("module:version", or "module"). */
    public PluginDefinition withMissingRequires(List<String> missing) {
        this.missingRequires = List.copyOf(missing);
        return this;
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

    public PluginSource getSource() {
        return source;
    }

    public boolean isIgnored() {
        return ignored;
    }

    public List<ModuleEntry> getModules() {
        return modules;
    }

    /** The plugins this one was loaded over, as "name version" (or "name" without version). */
    public List<String> getPluginDependencies() {
        return pluginDependencies;
    }

    public List<String> getMissingRequires() {
        return missingRequires;
    }
}
