package com.copybot.engine.listing;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;

/** Receives the listed items that failed in the listing pipe (e.g. an archive that cannot be unfolded). */
@FunctionalInterface
public interface ListingFailures {

    void failed(WorkItem item, CopybotException cause);
}
