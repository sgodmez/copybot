package com.copybot.engine;

import com.copybot.plugin.api.action.WorkItem;

import java.util.List;

/** What the process steps would produce from one item, by their dry run (spec pattern-helper §4.2). */
public sealed interface Projection {

    /** One or more produced items (copies: never the real item). */
    record Projected(List<WorkItem> items) implements Projection {
        public Projected {
            items = List.copyOf(items);
        }
    }

    /** A step returned no item. */
    record Filtered(String action) implements Projection {
    }

    /** A step does not support the dry run; before: the items before that step. */
    record Unsupported(String action, List<WorkItem> before) implements Projection {
        public Unsupported {
            before = List.copyOf(before);
        }
    }

    /** A dry run threw. */
    record Failed(String action, String message) implements Projection {
    }
}
