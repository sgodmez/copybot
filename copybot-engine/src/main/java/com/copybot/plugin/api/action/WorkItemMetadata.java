package com.copybot.plugin.api.action;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record WorkItemMetadata(
        Map<String, Object> raw,
        Map<String, String> display
) {

    public static final String CAPTURE_DATE = "captureDate";
    public static final String LAST_MODIFIED = "lastModified";
    /** raw key: SHA-256 of the content (hex), computed by file.write during the copy. */
    public static final String SHA256 = "sha256";

    public WorkItemMetadata() {
        this(new HashMap<>(), new HashMap<>());
    }

    public void setSize(long size) {
        raw.put("size", size);
        display.put("size", String.valueOf(size));
    }

    public Long getSize() {
        return (Long) raw.getOrDefault("size", null);
    }

    /** Shows the time in the zone of this computer: for file dates, which hold no zone. */
    public void setTime(String key, Instant time) {
        setTime(key, time.atZone(ZoneId.systemDefault()));
    }

    /** Stores the instant; shows the wall time of {@code time}'s own zone (e.g. the camera clock). */
    public void setTime(String key, ZonedDateTime time) {
        raw.put(key, time.toInstant());

        var localDateTime = time.toLocalDateTime();
        display.put(key + ".Y", String.format("%04d", localDateTime.getYear()));
        display.put(key + ".y", String.format("%02d", localDateTime.getYear() % 100));
        display.put(key + ".m", String.format("%02d", localDateTime.getMonthValue()));
        display.put(key + ".D", String.format("%02d", localDateTime.getDayOfMonth()));
        // TODO : more !
    }

    public Optional<Instant> getTime(String key) {
        return raw.get(key) instanceof Instant instant ? Optional.of(instant) : Optional.empty();
    }
}
