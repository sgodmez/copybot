package com.copybot.plugin.api.action;

import java.util.function.Consumer;

public interface IInAction extends IAction {

    /**
     * Function that produce work items to the provided consumer.
     * Action ends when returns.
     */
    void listFiles(Consumer<WorkItem> workItemConsumer);

    /**
     * As {@link #listFiles(Consumer)}, to a sink that also takes the end of each folder. The default feeds the
     * items to {@link #listFiles(Consumer)} and signals no folder.
     */
    default void listFiles(ListingSink sink) {
        listFiles(sink::item);
    }

    /**
     * Whether {@link #listFiles(ListingSink)} calls {@link ListingSink#folderDone} (an action that overrides it
     * must say so); false: the engine settles groups at the end of the listing.
     */
    default boolean signalsFolders() {
        return false;
    }


}