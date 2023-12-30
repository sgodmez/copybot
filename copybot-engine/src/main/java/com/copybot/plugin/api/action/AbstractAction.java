package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;

import java.util.ResourceBundle;
import java.util.function.Consumer;

public abstract class AbstractAction implements IAction {
    private Consumer<WorkStatus> statusWatcher;

    private String actualActionName;

    private IPlugin plugin;

    protected final void updateStatus(WorkStatus status) {
        actualActionName = status.actionName();
        statusWatcher.accept(status);
    }

    protected final void updatePercent(int actionPercent) {
        WorkStatus ws = new WorkStatus(actualActionName, actionPercent);
        statusWatcher.accept(ws);
    }

    @Override
    public final void setStatusWatcher(Consumer<WorkStatus> watcher) {
        this.statusWatcher = statusWatcher;
    }

    @Override
    public void setPlugin(IPlugin plugin) {
        this.plugin = plugin;
    }

    public IPlugin getPlugin() {
        return plugin;
    }

    public ResourceBundle getResourceBundle() {
        return getPlugin().getResourceBundle();
    }
}
