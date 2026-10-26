package com.copybot.ui.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** What the About dialog and the log file rely on, without a display. */
public class AppInfoTest {

    /** The build filters version.properties: the version of the project, never the placeholder. */
    @Test
    public void theVersionIsTheOneOfTheProject() {
        String version = AppInfo.version();
        assertNotEquals(AppInfo.UNKNOWN, version);
        assertTrue(version.matches("\\d+\\.\\d+.*"), version);
    }

    @Test
    public void anUnfilteredOrMissingVersionIsUnknown() throws IOException {
        assertEquals(AppInfo.UNKNOWN, AppInfo.version(null));
        assertEquals(AppInfo.UNKNOWN, AppInfo.version(stream("version=${project.version}")));
        assertEquals(AppInfo.UNKNOWN, AppInfo.version(stream("other=1")));
        assertEquals("2.1.0", AppInfo.version(stream("version=2.1.0\n")));
    }

    @Test
    public void theJavaVersionIsShown() {
        assertTrue(AppInfo.javaVersion().startsWith(String.valueOf(Runtime.version().feature())), AppInfo.javaVersion());
    }

    @Test
    public void anErrorWithoutMessageIsNamedByItsClass() {
        assertEquals("broken", PopinUtil.messageOf(new IllegalStateException("broken")));
        assertEquals("java.lang.IllegalStateException", PopinUtil.messageOf(new IllegalStateException()));
        assertEquals("java.lang.StackOverflowError", PopinUtil.messageOf(new StackOverflowError(" ")));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
