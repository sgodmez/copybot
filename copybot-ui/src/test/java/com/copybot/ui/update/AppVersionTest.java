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
    public void numericQualifierSegmentsAreComparedAsNumbers() {
        assertTrue(AppVersion.compare("1.0.0-beta.2", "1.0.0-beta.10") < 0);
        assertTrue(AppVersion.compare("1.0.0-beta.10", "1.0.0-beta.2") > 0);
        assertEquals(0, AppVersion.compare("1.0.0-beta.3", "1.0.0-beta.3"));
    }

    @Test
    public void textQualifierSegmentsAreComparedAlphabetically() {
        assertTrue(AppVersion.compare("1.0.0-alpha.5", "1.0.0-beta.1") < 0);
        assertTrue(AppVersion.compare("1.0.0-beta.9", "1.0.0-rc.1") < 0);
        assertTrue(AppVersion.compare("1.0.0-rc.1", "1.0.0-beta.9") > 0);
    }

    /** As in SemVer: fewer segments sort first when the others are equal, a number sorts before a word. */
    @Test
    public void aShorterQualifierAndANumberSortFirst() {
        assertTrue(AppVersion.compare("1.0.0-beta", "1.0.0-beta.1") < 0);
        assertTrue(AppVersion.compare("1.0.0-beta.1", "1.0.0-beta.x") < 0);
    }

    @Test
    public void theFinalReleaseIsNewerThanAnyOfItsBetas() {
        assertTrue(AppVersion.compare("1.3.0", "1.3.0-beta.10") > 0);
        assertTrue(AppVersion.compare("1.3.0-rc.1", "1.3.0") < 0);
        assertTrue(AppVersion.compare("1.3.0-beta.1", "1.2.9") > 0);
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
