package com.copybot.plugin.api.action;

import com.copybot.plugin.api.definition.IPlugin;

import java.util.ResourceBundle;

public abstract class AbstractAction implements IAction {

    // set once by the engine before the action is used, read from the virtual threads running it
    private volatile IPlugin plugin;

    /**
     * Reports the status of the item whose step runs on this thread ({@link ItemProgress#current()}): one action
     * instance serves every item concurrently, the report goes to the item the calling thread works on. Into the
     * void outside a step of the engine, or from a thread the action started itself (hand
     * {@link ItemProgress#current()} over to it instead).
     */
    protected final void updateStatus(WorkStatus status) {
        ItemProgress.current().report(status);
    }

    /** Reports a percent of the current item, with the action name of its previous status ({@link #updateStatus}). */
    protected final void updatePercent(int actionPercent) {
        ItemProgress.current().percent(actionPercent);
    }

    @Override
    public void setPlugin(IPlugin plugin) {
        this.plugin = plugin;
    }

    /** The plugin of this action, set by the engine when it creates the action; null for an action built by hand. */
    public IPlugin getPlugin() {
        return plugin;
    }

    /**
     * The bundle of the plugin of this action.
     *
     * @throws IllegalStateException the action was built by hand, without plugin
     */
    public ResourceBundle getResourceBundle() {
        IPlugin current = plugin;
        if (current == null) {
            throw new IllegalStateException(getClass().getName() + " has no plugin: it was not created by the engine");
        }
        return current.getResourceBundle();
    }
}
