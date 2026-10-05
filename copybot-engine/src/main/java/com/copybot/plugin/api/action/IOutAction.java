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
     * <p>
     * May be called from any thread, concurrently with {@link #write} / {@link #writeItem} of other items
     * (e.g. the desktop UI shows the target of each listed item while a copy runs): it must be thread-safe
     * and free of side effects (no file created, no counter or state changed).
     */
    default Optional<Path> resolveTarget(WorkItem workItem) {
        return Optional.empty();
    }

    /**
     * What the write of this item would find at its target, told by the plan before the copy (spec conflict-check
     * §3), without writing anything: free, or an existing target and what the copy will do with it. The default
     * cannot tell ({@link TargetCheck#UNKNOWN}): existing plugins keep working, nothing is announced.
     * <p>
     * Same contract as {@link #resolveTarget}: thread-safe, free of side effects, callable while other items are
     * written. A failure may be thrown: the engine takes it as {@link TargetCheck#UNKNOWN}.
     *
     * @param compareContent false: existence and size only, one access to the target; true: the action's own
     *                       comparison, the one its write would make (may read the existing target)
     */
    default TargetCheck checkTarget(WorkItem workItem, boolean compareContent) {
        return TargetCheck.UNKNOWN;
    }

    /**
     * Whether the directory of the targets ({@link #resolveTarget}) depends on the item: a fixed directory (e.g. a
     * pattern {@code out/{name}}) tells nothing about what was already imported, the resume from the destination then
     * selects every file. Empty when the action cannot tell (the default).
     */
    default Optional<Boolean> targetDirectoryVaries() {
        return Optional.empty();
    }
}