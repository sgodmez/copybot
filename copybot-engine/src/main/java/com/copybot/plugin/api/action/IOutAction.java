package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Optional;

public interface IOutAction extends IAction {

    void writeItem(WorkItem workItem);

    /**
     * Writes one item; the engine calls this method, not {@link #writeItem}. The default calls
     * {@link #writeItem} and reports the item as written, target unknown: existing plugins keep working
     * unchanged. Override it to report an item skipped (e.g. already at the destination) or to use the
     * execution context. A failure is thrown, as with {@link #writeItem}: the item ends ERROR.
     * A null result is treated as written with an unknown target.
     */
    default WriteResult write(WorkItem workItem, WriteContext context) {
        writeItem(workItem);
        return WriteResult.written(null);
    }

    /**
     * Where {@link #writeItem} would write this item, without writing anything. Used to detect what
     * was already imported. Empty when the action cannot tell (the default).
     */
    default Optional<Path> resolveTarget(WorkItem workItem) {
        return Optional.empty();
    }
}