package com.copybot.ui.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The zones of the time zone dialog, with a label that tells their current UTC offset ("Europe/Paris (UTC+01:00)"). */
public final class ZoneLabels {

    /** A zone and the label shown for it. */
    public record Entry(ZoneId zone, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private ZoneLabels() {
    }

    private static final Set<String> REGIONS = Set.of("Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific");

    private static boolean isRegion(String id) {
        int slash = id.indexOf('/');
        return slash > 0 ? REGIONS.contains(id.substring(0, slash)) : id.equals("UTC");
    }

    /** "Europe/Paris (UTC+01:00)": the zone and its offset at that instant; a fixed offset is just "UTC+09:00". */
    public static String label(ZoneId zone, Instant at) {
        ZoneOffset offset = zone.getRules().getOffset(at);
        String utc = "UTC" + (offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId());
        return zone instanceof ZoneOffset ? utc : zone.getId() + " (" + utc + ")";
    }

    /** The region zones (Europe/Paris, not Etc/GMT+6 whose sign is inverted, nor SystemV/*), sorted by id. */
    public static List<Entry> entries(Instant at) {
        return ZoneId.getAvailableZoneIds().stream().filter(ZoneLabels::isRegion).sorted()
                .map(id -> new Entry(ZoneId.of(id), label(ZoneId.of(id), at))).toList();
    }

    /** Whether the text filter (case and surrounding spaces ignored) is found in the zone id or in the label. */
    public static boolean matches(Entry entry, String filter) {
        String wanted = filter.trim().toLowerCase(Locale.ROOT);
        return entry.zone().getId().toLowerCase(Locale.ROOT).contains(wanted) || entry.label().toLowerCase(Locale.ROOT).contains(wanted);
    }
}
