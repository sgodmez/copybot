package com.copybot.engine.sample;

import com.copybot.engine.DryRunner;
import com.copybot.plugin.api.action.WorkItem;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One item of a {@link Sample}: its source, its name now, its display metadata (what out patterns can use) and
 * the error that stopped its analysis or dry run, if any.
 */
public record SampleItem(String sourceName, String name, Map<String, String> display, Optional<String> error) {

    public SampleItem {
        Map<String, String> copy = new HashMap<>();
        display.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        display = Map.copyOf(copy);
    }

    static SampleItem of(String source, WorkItem item, Optional<String> error) {
        return new SampleItem(source, name(item), item.getMetadatas().display(), error);
    }

    /** display.name, else the name of the source. */
    static String name(WorkItem item) {
        return DryRunner.itemName(item);
    }
}
