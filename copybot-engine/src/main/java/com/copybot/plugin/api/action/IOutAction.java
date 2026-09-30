package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Optional;

public interface IOutAction extends IAction {

    void writeItem(WorkItem workItem);

    /**
     * Where {@link #writeItem} would write this item, without writing anything. Used to detect what
     * was already imported. Empty when the action cannot tell (the default).
     */
    default Optional<Path> resolveTarget(WorkItem workItem) {
        return Optional.empty();
    }
}