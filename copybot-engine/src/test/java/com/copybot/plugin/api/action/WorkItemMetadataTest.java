package com.copybot.plugin.api.action;

import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    public void getTimeIsEmptyForUnknownOrNonInstantValues() {
        WorkItemMetadata m = new WorkItemMetadata();
        m.raw().put("weird", "not an instant");

        assertTrue(m.getTime("missing").isEmpty());
        assertTrue(m.getTime("weird").isEmpty());
    }
}
