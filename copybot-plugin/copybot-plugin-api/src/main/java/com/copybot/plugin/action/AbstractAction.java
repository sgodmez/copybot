package com.copybot.plugin.action;

import java.util.function.Consumer;

public abstract class AbstractAction implements IAction {
    private Consumer<WorkStatus> statusWatcher;

    protected final void updateStatus(WorkStatus status) {
        statusWatcher.accept(status);
    }

    @Override
    public final void setStatusWatcher(Consumer<WorkStatus> watcher) {
        this.statusWatcher = statusWatcher;
    }
}
