package com.copybot.plugin.action;

import java.util.function.Consumer;

public interface IAction {

    default void beforeAll() {
        // nothing by default
    }

    default void beforeEach(WorkItem item) {
        // nothing by default
    }

    default void afterEach(WorkItem item) {
        // nothing by default
    }

    default void afterAll() {
        // nothing by default
    }

    default void setStatusWatcher(Consumer<WorkStatus> watcher) {
        // nothing by default
    }
}
