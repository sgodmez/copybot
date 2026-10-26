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
    public void anInvalidDocumentIsUnreadable() {
        for (String json : new String[]{"not json", "[]", "{}", "{\"tag_name\": \"v1\"}"}) {
            UpdateException e = assertThrows(UpdateException.class, () -> Release.parse(json), json);
            assertEquals("update.error.unreadable", e.key());
        }
    }
}
