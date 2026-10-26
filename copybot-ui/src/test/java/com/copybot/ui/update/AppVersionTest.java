package com.copybot.ui.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AppVersionTest {

    @Test
    public void numbersAreComparedOneByOne() {
        assertTrue(AppVersion.compare("1.0.0", "1.0.1") < 0);
        assertTrue(AppVersion.compare("1.10.0", "1.9.0") > 0);
        assertTrue(AppVersion.compare("2.0.0", "1.99.99") > 0);
    }

    @Test
    public void missingNumbersAreZero() {
        assertEquals(0, AppVersion.compare("1.0", "1.0.0"));
    }

    @Test
    public void aQualifierSortsBeforeTheRelease() {
        assertTrue(AppVersion.compare("1.0.0-beta", "1.0.0") < 0);
        assertTrue(AppVersion.compare("1.0.0", "1.0.0-beta") > 0);
    }

    @Test
    public void onlyAPublishedVersionIsARelease() {
        assertFalse(AppVersion.isRelease("1.0-SNAPSHOT"));
        assertFalse(AppVersion.isRelease("?"));
        assertFalse(AppVersion.isRelease(""));
        assertFalse(AppVersion.isRelease(null));
        assertTrue(AppVersion.isRelease("1.2.3"));
    }
}
