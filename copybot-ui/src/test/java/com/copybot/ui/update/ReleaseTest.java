package com.copybot.ui.update;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

public class ReleaseTest {

    private static final String JSON = """
            {"tag_name": "v1.2.0", "html_url": "https://github.com/sgodmez/copybot/releases/tag/v1.2.0",
             "assets": [
              {"name": "copybot-1.2.0-setup.exe", "browser_download_url": "https://example.com/setup.exe",
               "size": 42, "digest": "sha256:ABC123"},
              {"name": "copybot-1.2.0-windows-x64.zip", "browser_download_url": "https://example.com/app.zip",
               "size": 7, "digest": null}
             ]}""";

    @Test
    public void aGitHubReleaseIsRead() throws UpdateException {
        Release release = Release.parse(JSON);

        assertEquals("1.2.0", release.version());
        assertEquals(URI.create("https://github.com/sgodmez/copybot/releases/tag/v1.2.0"), release.notesUrl());
        assertEquals(2, release.assets().size());
        Release.Asset setup = release.asset("copybot-1.2.0-setup.exe").orElseThrow();
        assertEquals("abc123", setup.sha256());
        assertEquals(42, setup.size());
        assertEquals(URI.create("https://example.com/setup.exe"), setup.url());
        assertNull(release.asset("copybot-1.2.0-windows-x64.zip").orElseThrow().sha256());
        assertTrue(release.asset("other").isEmpty());
    }

    @Test
    public void theNotesAreTheBodyOfTheRelease() throws UpdateException {
        String body = "{\"tag_name\": \"v1.2.0\", \"html_url\": \"https://example.com/n\", \"body\": \"What is new\\nA line\"}";
        assertEquals("What is new\nA line", Release.parse(body).notes());
        assertEquals("", Release.parse(JSON).notes());
        assertEquals("", Release.parse(body.replace("\"What is new\\nA line\"", "null")).notes());
    }

    @Test
    public void aPreReleaseIsMarked() throws UpdateException {
        assertFalse(Release.parse(JSON).prerelease());
        String beta = "{\"tag_name\": \"v1.3.0-beta.1\", \"html_url\": \"https://example.com/n\", \"prerelease\": true}";
        Release release = Release.parse(beta);
        assertTrue(release.prerelease());
        assertEquals("1.3.0-beta.1", release.version());
    }

    private static String entry(String tag, boolean prerelease, boolean draft) {
        return "{\"tag_name\": \"" + tag + "\", \"html_url\": \"https://example.com/" + tag + "\", \"prerelease\": "
                + prerelease + ", \"draft\": " + draft + ", \"assets\": []}";
    }

    /** The answer of {@code releases}: GitHub lists them newest first by date, not by version. */
    @Test
    public void theNewestOfAListIsTheHighestVersionPreReleasesIncluded() throws UpdateException {
        String json = "[" + entry("v1.3.0-beta.2", true, false) + "," + entry("v1.3.0-beta.10", true, false) + ","
                + entry("v1.2.0", false, false) + "]";

        Release newest = Release.parseNewest(json);

        assertEquals("1.3.0-beta.10", newest.version());
        assertTrue(newest.prerelease());
    }

    @Test
    public void theFinalReleaseOutranksItsBetas() throws UpdateException {
        String json = "[" + entry("v1.3.0-beta.10", true, false) + "," + entry("v1.3.0", false, false) + "]";

        Release newest = Release.parseNewest(json);

        assertEquals("1.3.0", newest.version());
        assertFalse(newest.prerelease());
    }

    @Test
    public void draftsAndTagsThatAreNotVersionsAreSkipped() throws UpdateException {
        String json = "[" + entry("v2.0.0", false, true) + "," + entry("nightly", true, false) + ","
                + entry("v1.2.0", false, false) + "]";

        assertEquals("1.2.0", Release.parseNewest(json).version());
    }

    @Test
    public void aListWithoutAReleaseMeansNoReleaseYet() {
        for (String json : new String[]{"[]", "[" + entry("v2.0.0", false, true) + "]"}) {
            UpdateException e = assertThrows(UpdateException.class, () -> Release.parseNewest(json), json);
            assertEquals("update.no-release", e.key());
        }
    }

    @Test
    public void anInvalidListIsUnreadable() {
        for (String json : new String[]{"not json", "{}", "[1]", "[{\"tag_name\": \"v1.0.0\"}]"}) {
            UpdateException e = assertThrows(UpdateException.class, () -> Release.parseNewest(json), json);
            assertEquals("update.error.unreadable", e.key());
        }
    }

    @Test
    public void anInvalidDocumentIsUnreadable() {
        for (String json : new String[]{"not json", "[]", "{}", "{\"tag_name\": \"v1\"}"}) {
            UpdateException e = assertThrows(UpdateException.class, () -> Release.parse(json), json);
            assertEquals("update.error.unreadable", e.key());
        }
    }
}
