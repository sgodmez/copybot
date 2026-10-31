package com.copybot.ui.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ZoneLabelsTest {

    private static final Instant WINTER = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    public void labelTellsTheCurrentOffset() {
        assertEquals("Europe/Paris (UTC+01:00)", ZoneLabels.label(ZoneId.of("Europe/Paris"), WINTER));
        assertEquals("Europe/Paris (UTC+02:00)", ZoneLabels.label(ZoneId.of("Europe/Paris"), Instant.parse("2026-07-15T12:00:00Z")));
        assertEquals("America/Mexico_City (UTC-06:00)", ZoneLabels.label(ZoneId.of("America/Mexico_City"), WINTER));
        assertEquals("Asia/Kolkata (UTC+05:30)", ZoneLabels.label(ZoneId.of("Asia/Kolkata"), WINTER));
        assertEquals("UTC+09:00", ZoneLabels.label(ZoneOffset.ofHours(9), WINTER));
        assertEquals("UTC (UTC+00:00)", ZoneLabels.label(ZoneId.of("UTC"), WINTER));
    }

    @Test
    public void entriesAreSortedRegionsWithoutTheEtcTrap() {
        List<ZoneLabels.Entry> entries = ZoneLabels.entries(WINTER);
        List<String> ids = entries.stream().map(e -> e.zone().getId()).toList();

        assertTrue(ids.contains("Europe/Paris"));
        assertTrue(ids.stream().noneMatch(id -> id.startsWith("Etc/") || id.startsWith("SystemV/")), "no Etc, no SystemV");
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals("Europe/Paris (UTC+01:00)", entries.stream().filter(e -> e.zone().getId().equals("Europe/Paris")).findFirst().orElseThrow().label());
    }

    @Test
    public void filterMatchesTheIdAndTheLabel() {
        ZoneLabels.Entry paris = new ZoneLabels.Entry(ZoneId.of("Europe/Paris"), "Europe/Paris (UTC+01:00)");

        assertTrue(ZoneLabels.matches(paris, ""));
        assertTrue(ZoneLabels.matches(paris, "  PARIS "));
        assertTrue(ZoneLabels.matches(paris, "utc+01"));
        assertFalse(ZoneLabels.matches(paris, "london"));
    }
}
