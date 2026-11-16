package com.copybot.plugin.gpszone.actions;

import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.gpszone.ZoneFinder;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Suggests the time zone of the place for a photo whose camera was badly set (spec gps-zone-check §1, §6): to
 * place after metadata-extractor.extract, whose raw GPS values and recorded offset it reads. The suggestion
 * ({@link WorkItemMetadata#SUGGESTED_ZONE}) is removed at each analysis, then set only when the GPS is valid and
 * not at (0, 0), the camera recorded an offset (never for a video), the GPS UTC time is within
 * {@link #GPS_TIME_TOLERANCE} of the capture instant, the place has a zone far enough from a boundary
 * ({@link ZoneFinder#zoneAt}), its offset at that instant differs from the recorded one, and the zone was not
 * corrected in the plan ({@link WorkItemMetadata#CAPTURE_ZONE}).
 */
public class CheckGpsZone extends AbstractAction implements IAnalyzeAction {

    /** A camera clock whose wall time was set by hand is wrong by whole hours; a GPS fix can be minutes old. */
    static final Duration GPS_TIME_TOLERANCE = Duration.ofMinutes(15);

    private final ZoneFinder finder;

    public CheckGpsZone() {
        this(ZoneFinder.world());
    }

    public CheckGpsZone(ZoneFinder finder) {
        this.finder = finder;
    }

    @Override
    public void doAnalyze(WorkItem item) {
        WorkItemMetadata metadatas = item.getMetadatas();
        metadatas.raw().remove(WorkItemMetadata.SUGGESTED_ZONE);
        suggestion(metadatas).ifPresent(zone -> metadatas.raw().put(WorkItemMetadata.SUGGESTED_ZONE, zone));
    }

    private Optional<ZoneId> suggestion(WorkItemMetadata metadatas) {
        Map<String, Object> raw = metadatas.raw();
        if (raw.containsKey(WorkItemMetadata.CAPTURE_ZONE)
                || !Boolean.TRUE.equals(raw.get(WorkItemMetadata.GPS_VALID))
                || !(raw.get(WorkItemMetadata.GPS_LATITUDE) instanceof Double latitude)
                || !(raw.get(WorkItemMetadata.GPS_LONGITUDE) instanceof Double longitude)
                || (latitude == 0 && longitude == 0)
                || !(raw.get(WorkItemMetadata.RECORDED_OFFSET) instanceof ZoneOffset recorded)) {
            return Optional.empty();
        }
        Optional<Instant> capture = metadatas.getTime(WorkItemMetadata.CAPTURE_DATE);
        Optional<Instant> gps = metadatas.getTime(WorkItemMetadata.GPS_TIME);
        if (capture.isEmpty() || gps.isEmpty()
                || Duration.between(gps.get(), capture.get()).abs().compareTo(GPS_TIME_TOLERANCE) >= 0) {
            return Optional.empty();
        }
        // the boundaries are loaded only here, for a file whose GPS can tell
        return finder.zoneAt(latitude, longitude)
                .filter(zone -> !zone.getRules().getOffset(capture.get()).equals(recorded));
    }
}
