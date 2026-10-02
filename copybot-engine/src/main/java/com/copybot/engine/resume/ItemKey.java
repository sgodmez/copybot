package com.copybot.engine.resume;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Resume ordering key of an item: capture date (to the second) then file name. The name only breaks
 * ties between shots of the same second, so a wrapping file counter (DSC_9999 -> DSC_0001) is harmless.
 */
public record ItemKey(Instant date, String name) implements Comparable<ItemKey> {

    private static final Comparator<ItemKey> ORDER = Comparator.comparing(ItemKey::date).thenComparing(ItemKey::name);

    public ItemKey {
        date = Objects.requireNonNull(date, "date").truncatedTo(ChronoUnit.SECONDS);
        name = name == null ? "" : name;
    }

    /** Capture date when an analysis provided it, file modification date otherwise; empty without any date. */
    public static Optional<ItemKey> of(WorkItem item) {
        WorkItemMetadata metadata = item.getMetadatas();
        return metadata.getTime(WorkItemMetadata.CAPTURE_DATE)
                .or(() -> metadata.getTime(WorkItemMetadata.LAST_MODIFIED))
                .map(date -> new ItemKey(date, item.getNameDisplay()));
    }

    @Override
    public int compareTo(ItemKey other) {
        return ORDER.compare(this, other);
    }
}
