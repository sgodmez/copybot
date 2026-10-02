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
}
