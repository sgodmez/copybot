package com.copybot.plugin.api.action;

/** Receives what a listing produces: the items, and optionally the end of each folder. */
public interface ListingSink {

    /** One listed item. */
    void item(WorkItem item);

    /**
     * Promises that everything under {@code folder}, sub-folders included, has been emitted by {@link #item}: the
     * engine may then settle what it held back for that folder (e.g. a group waiting for its companions). Only
     * called by an action whose {@link IInAction#signalsFolders()} is true; {@code folder} is the
     * {@link WorkItem#getListedFolder()} of the items it covers.
     */
    void folderDone(String folder);
}
