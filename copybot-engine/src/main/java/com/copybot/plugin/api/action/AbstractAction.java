package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;

import java.util.ResourceBundle;
import java.util.function.Consumer;

public abstract class AbstractAction implements IAction {
    // set by the engine, read from the virtual threads running the action
    private volatile Consumer<WorkStatus> statusWatcher;

    private String actualActionName;

    private IPlugin plugin;

    protected final void updateStatus(WorkStatus status) {
        actualActionName = status.actionName();
        publish(status);
    }

    protected final void updatePercent(int actionPercent) {
        publish(new WorkStatus(actualActionName, actionPercent));
    }

    /**
     * Reporting progress must never be able to break an action: observing is optional, so an action
     * that reports its status while nobody listens simply reports into the void.
     */
    private void publish(WorkStatus status) {
        Consumer<WorkStatus> watcher = statusWatcher;
        if (watcher != null) {
            watcher.accept(status);
        }
    }

    @Override
    public final void setStatusWatcher(Consumer<WorkStatus> watcher) {
        this.statusWatcher = watcher; // was assigning the field to itself: the watcher was never stored
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
