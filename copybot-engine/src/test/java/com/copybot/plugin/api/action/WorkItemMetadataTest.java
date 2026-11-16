package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class WorkItemMetadataTest {

    @Test
    public void setTimeStoresTheRawInstantUnderItsOwnKey() {
        WorkItemMetadata m = new WorkItemMetadata();
        Instant capture = Instant.parse("2026-09-28T15:42:10Z");
        Instant modified = Instant.parse("2026-09-29T08:00:00Z");

        m.setTime(WorkItemMetadata.CAPTURE_DATE, capture);
        m.setTime(WorkItemMetadata.LAST_MODIFIED, modified);

        assertEquals(Optional.of(capture), m.getTime("captureDate"));
        assertEquals(Optional.of(modified), m.getTime("lastModified"));
        assertFalse(m.raw().containsKey("key"), "the literal key \"key\" must not be used anymore");
        assertEquals("2026", m.display().get("captureDate.Y"));
    }

    @Test
    public void setTimeWithAZoneShowsTheWallTimeOfThatZone() {
        WorkItemMetadata m = new WorkItemMetadata();
        // 23:30 in Mexico City is already the next day in Paris
        ZonedDateTime shot = ZonedDateTime.parse("2026-10-25T23:30:00-06:00");

        m.setTime(WorkItemMetadata.CAPTURE_DATE, shot);

        assertEquals(Optional.of(shot.toInstant()), m.getTime("captureDate"));
        assertEquals("2026", m.display().get("captureDate.Y"));
        assertEquals("10", m.display().get("captureDate.m"));
        assertEquals("25", m.display().get("captureDate.D"));
    }

    @Test
    public void getTimeIsEmptyForUnknownOrNonInstantValues() {
        WorkItemMetadata m = new WorkItemMetadata();
        m.raw().put("weird", "not an instant");

        assertTrue(m.getTime("missing").isEmpty());
        assertTrue(m.getTime("weird").isEmpty());
    }

    @Test
    public void getZoneIsEmptyForUnknownOrNonZoneValues() {
        WorkItemMetadata m = new WorkItemMetadata();
        m.raw().put(WorkItemMetadata.CAPTURE_ZONE, ZoneId.of("Europe/Paris"));
        m.raw().put(WorkItemMetadata.CAMERA_CLOCK, "not a zone");

        assertEquals(Optional.of(ZoneId.of("Europe/Paris")), m.getZone(WorkItemMetadata.CAPTURE_ZONE));
        assertTrue(m.getZone(WorkItemMetadata.CAMERA_CLOCK).isEmpty());
        assertTrue(m.getZone("missing").isEmpty());
    }
}
