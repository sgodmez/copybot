package com.copybot.plugin.api.action;

/** Receives what a listing produces: the items, and optionally the end of each folder. */
public interface ListingSink {

    /**
     * Where the source starts: sent at most once, before any item, by a source that knows the root of its listing, in
     * the form of {@link WorkItem#getListedFolder()}. The engine then knows that what lies above it is out of the
     * listing (e.g. a web page's {@code _files} folder is only held for a page listed beside it). Default: ignored.
     */
    default void root(String folder) {
    }

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
