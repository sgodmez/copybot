package com.copybot.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VersionUtilTest {

    @Test
    public void versionsCompareNumericallyNotAsStrings() {
        assertTrue(VersionUtil.VERSION_ORDER.compare("1.10", "1.9") > 0, "1.10 is more recent than 1.9");
        assertTrue(VersionUtil.VERSION_ORDER.compare("2.0", "10.0") < 0);
        assertEquals(0, VersionUtil.VERSION_ORDER.compare("1.2.3", "1.2.3"));
    }

    @Test
    public void newestFirstPutsMissingAndUnparsableVersionsLast() {
        List<String> versions = new ArrayList<>(Arrays.asList("1.9", null, "1.10", "dev", "0.1"));

        versions.sort(VersionUtil.VERSION_ORDER.reversed());

        assertEquals(Arrays.asList("1.10", "1.9", "0.1", "dev", null), versions);
    }

    @Test
    public void aMajorOnlyRequirementAcceptsAnyMinorOfThatMajor() {
        assertTrue(VersionUtil.isCompatible("1.2", "1", false));
        assertTrue(VersionUtil.isCompatible("1.2", "1", true));
        assertFalse(VersionUtil.isCompatible("2.0", "1", false));
    }

    @Test
    public void aRequirementEndingWithADotIsMajorOnly() {
        assertTrue(VersionUtil.isCompatible("1.2", "1.", false));
        assertTrue(VersionUtil.isCompatible("1.2", "1.", true));
    }

    @Test
    public void aVersionWithoutMinorIsMinorZero() {
        assertTrue(VersionUtil.isCompatible("2", "2.0", true));
        assertFalse(VersionUtil.isCompatible("2", "2.1", false));
    }

    @Test
    public void minorAndQualifiersStillCompare() {
        assertTrue(VersionUtil.isCompatible("1.3", "1.2", false), "a more recent minor is compatible");
        assertFalse(VersionUtil.isCompatible("1.3", "1.2", true), "strict: same minor only");
        assertFalse(VersionUtil.isCompatible("1.1", "1.2", false));
        assertTrue(VersionUtil.isCompatible("1.2.5-SNAPSHOT", "1.2.1", true));
        assertTrue(VersionUtil.isCompatible("dev", "dev", false), "an uncommon pattern compares as is");
        assertFalse(VersionUtil.isCompatible("dev", "1.0", false));
    }
}
