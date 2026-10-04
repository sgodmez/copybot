package com.copybot.engine;

import com.copybot.plugin.api.action.WorkItem;

import java.util.List;

/**
 * What the process steps would produce from one item, by their dry run (spec pattern-helper §4.2), with the
 * trace of the steps that ran: the detail of the planned processing shown by the desktop UI.
 */
public sealed interface Projection {

    /** The process steps that produced items, in order; the step that ended the dry run is not in it. */
    List<Step> trace();

    /** One process step of the dry run: its action and the names of the items it produced. */
    record Step(String action, List<String> produced) {
        public Step {
            produced = List.copyOf(produced);
        }
    }

    /** One or more produced items (copies: never the real item). */
    record Projected(List<WorkItem> items, List<Step> trace) implements Projection {
        public Projected {
            items = List.copyOf(items);
            trace = List.copyOf(trace);
        }

        public Projected(List<WorkItem> items) {
            this(items, List.of());
        }
    }

    /** A step returned no item. */
    record Filtered(String action, List<Step> trace) implements Projection {
        public Filtered {
            trace = List.copyOf(trace);
        }

        public Filtered(String action) {
            this(action, List.of());
        }
    }

    /** A step does not support the dry run; before: the items before that step. */
    record Unsupported(String action, List<WorkItem> before, List<Step> trace) implements Projection {
        public Unsupported {
            before = List.copyOf(before);
            trace = List.copyOf(trace);
        }

        public Unsupported(String action, List<WorkItem> before) {
            this(action, before, List.of());
        }
    }

    /** A dry run threw. */
    record Failed(String action, String message, List<Step> trace) implements Projection {
        public Failed {
            trace = List.copyOf(trace);
        }

        public Failed(String action, String message) {
            this(action, message, List.of());
        }
    }
}
