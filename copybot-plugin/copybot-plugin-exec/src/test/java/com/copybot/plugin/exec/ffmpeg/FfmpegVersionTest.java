package com.copybot.plugin.exec.ffmpeg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FfmpegVersionTest {

    @Test
    public void theVersionOfAReleaseBuildIsRead() {
        FfmpegVersion btbn = FfmpegVersion.parse("ffmpeg version n8.1-7-g1234567-20261030 Copyright (c) 2000-2026"
                + " the FFmpeg developers\nbuilt with gcc");
        assertEquals("n8.1-7-g1234567-20261030", btbn.text());
        assertEquals(8, btbn.major());
        assertEquals(1, btbn.minor());
        assertTrue(btbn.atLeast(7, 1));
        assertTrue(btbn.atLeast(8, 1));
        assertFalse(btbn.atLeast(8, 2));

        FfmpegVersion debian = FfmpegVersion.parse("ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023");
        assertEquals(6, debian.major());
        assertEquals(1, debian.minor());
        assertFalse(debian.atLeast(7, 1));

        FfmpegVersion gyan = FfmpegVersion.parse("ffmpeg version 7.1-full_build-www.gyan.dev Copyright");
        assertTrue(gyan.atLeast(7, 1));

        FfmpegVersion major = FfmpegVersion.parse("ffmpeg version 8 Copyright");
        assertEquals(8, major.major());
        assertEquals(0, major.minor());
    }

    @Test
    public void aDevelopmentBuildCountsAsRecent() {
        FfmpegVersion master = FfmpegVersion.parse("ffmpeg version N-121234-gabcdef-20261030 Copyright (c)");
        assertTrue(master.development());
        assertTrue(master.atLeast(7, 1));

        FfmpegVersion dated = FfmpegVersion.parse("ffmpeg version 2024-03-14-git-abc123-full_build-www.gyan.dev Co");
        assertTrue(dated.development(), "a dated build is not version 2024");
        assertEquals(-1, dated.major());
        assertTrue(dated.atLeast(7, 1));
        assertTrue(dated.atLeast(99, 0));
    }

    @Test
    public void anUnknownVersionIsNeverRecentEnough() {
        FfmpegVersion odd = FfmpegVersion.parse("ffmpeg version custom-build Copyright");
        assertEquals("custom-build", odd.text());
        assertFalse(odd.atLeast(1, 0));

        FfmpegVersion none = FfmpegVersion.parse("something else entirely");
        assertEquals("", none.text());
        assertFalse(none.atLeast(1, 0));

        FfmpegVersion huge = FfmpegVersion.parse("ffmpeg version 99999999999.1 Copyright");
        assertFalse(huge.atLeast(1, 0), "a number that does not fit is unknown, not a failure");
    }
}
