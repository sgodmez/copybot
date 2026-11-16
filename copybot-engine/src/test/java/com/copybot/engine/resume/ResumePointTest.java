package com.copybot.engine.resume;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ResumePointTest {

    private static final ItemKey K1 = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "A");
    private static final ItemKey K2 = new ItemKey(Instant.parse("2026-09-28T10:00:00Z"), "B");
    private static final ItemKey K3 = new ItemKey(Instant.parse("2026-09-28T11:00:00Z"), "A");

    @Test
    public void allSelectsEverything() {
        assertTrue(ResumePoint.all().selects(K1));
        assertTrue(ResumePoint.all().selects(K3));
    }

    @Test
    public void afterIsExclusive() {
        ResumePoint p = ResumePoint.after(K2);
        assertFalse(p.selects(K1));
        assertFalse(p.selects(K2));
        assertTrue(p.selects(K3));
    }

    @Test
    public void fromIsInclusive() {
        ResumePoint p = ResumePoint.from(K2);
        assertFalse(p.selects(K1));
        assertTrue(p.selects(K2));
        assertTrue(p.selects(K3));
    }

    @Test
    public void afterAndFromRequireAKey() {
        assertThrows(NullPointerException.class, () -> ResumePoint.after(null));
        assertThrows(NullPointerException.class, () -> ResumePoint.from(null));
        assertThrows(NullPointerException.class, () -> new ResumePoint(null, K1));
    }
}
