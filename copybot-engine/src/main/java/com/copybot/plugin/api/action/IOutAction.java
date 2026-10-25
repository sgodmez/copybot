package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.util.Optional;

public interface IOutAction extends IAction {

    /**
     * Writes one item. Return {@link WriteResult#written} (a null result counts as written, target unknown), or
     * {@link WriteResult#skipped} with a reason (e.g. already at the destination: the item ends SKIPPED). A
     * failure is thrown: the item ends ERROR. The context tells the run ({@link WriteContext#runId()}) and takes
     * the progress of the item ({@link WriteContext#progress()}).
     */
    WriteResult write(WorkItem workItem, WriteContext context);

    /**
     * Where {@link #write} would write this item, without writing anything. Used to detect what
     * was already imported. Empty when the action cannot tell (the default).
     * <p>
     * May be called from any thread, concurrently with {@link #write} of other items
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

    /**
     * The pattern applied to an item without its own; empty: this out step does not take an own pattern
     * (spec own-out-pattern §1).
     */
    default Optional<String> defaultOutPattern() {
        return Optional.empty();
    }
}
