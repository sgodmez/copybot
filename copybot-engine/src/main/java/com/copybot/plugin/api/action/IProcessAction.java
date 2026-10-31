package com.copybot.plugin.api.action;

import java.util.List;
import java.util.Optional;

public interface IProcessAction extends IAction {

    List<WorkItem> doProcess(WorkItem item);

    /**
     * The expected effect of {@link #doProcess}, without writing anything nor any heavy work (spec
     * pattern-helper §4): used by "Prepare" and by the editor's sample. The item is a copy
     * ({@link WorkItem#copyForDryRun()}): modify it, return it and/or other copies (a fork), or an empty
     * list (filtered out). Empty (the default): this action does not support the dry run - never read as
     * "no effect". A null result counts as empty.
     */
    default Optional<List<WorkItem>> dryRun(WorkItem item) {
        return Optional.empty();
    }
}