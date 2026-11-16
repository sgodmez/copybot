package com.copybot.plugin.api.action;

/**
 * Where an action reports the progress of the one item it is working on: the plan view shows it while the item
 * runs ("Copying 42 %"). One action instance serves every item of its step, concurrently: the channel belongs to
 * the item, not to the action. The engine hands it over twice for each step of an item: as
 * {@link #current()} on the thread running the step (what {@link AbstractAction#updateStatus} and
 * {@link AbstractAction#updatePercent} use), and as {@link WriteContext#progress()} to an out step. A thread the
 * action starts itself does not see {@link #current()}: capture it first and hand it over.
 * <p>
 * Reporting never breaks an action: outside a step of the engine (a dry run, a test, a direct call) the channel
 * is {@link #NONE}, and the engine's channel never throws. Reports are cheap (the view is refreshed at most ten
 * times a second, with the last one).
 */
@FunctionalInterface
public interface ItemProgress {

    /** Reports into the void: outside a step of the engine. */
    ItemProgress NONE = status -> {
    };

    /**
     * Reports the status of the item. A null action name keeps the one of the previous report of the same step;
     * a negative percent means "running, progress unknown".
     */
    void report(WorkStatus status);

    /** Reports a percent of the current action (the action name of the previous report). */
    default void percent(int percent) {
        report(new WorkStatus(null, percent));
    }

    /** The channel of the item whose step runs on this thread, {@link #NONE} outside a step of the engine. */
    static ItemProgress current() {
        return ItemProgressScope.CURRENT.isBound() ? ItemProgressScope.CURRENT.get() : NONE;
    }

    /**
     * Runs the operation with this channel as {@link #current()} on this thread. Called by the engine around each
     * step of an item; usable by a test.
     */
    static <R, X extends Throwable> R callWith(ItemProgress progress, ScopedValue.CallableOp<? extends R, X> operation)
            throws X {
        return ScopedValue.where(ItemProgressScope.CURRENT, progress == null ? NONE : progress).call(operation);
    }
}
