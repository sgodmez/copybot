package com.copybot.plugin.action;

import com.copybot.plugin.definition.IPlugin;

import java.util.ResourceBundle;
import java.util.function.Consumer;

public abstract class AbstractAction implements IAction {
    private Consumer<WorkStatus> statusWatcher;

    private IPlugin plugin;

    protected final void updateStatus(WorkStatus status) {
        statusWatcher.accept(status);
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
