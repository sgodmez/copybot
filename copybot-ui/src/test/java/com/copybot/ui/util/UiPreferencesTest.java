package com.copybot.ui.util;

import com.copybot.ui.model.RecentPipelines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The preferences in a prefs.json of a temporary folder: the user's real preferences are never touched. */
public class UiPreferencesTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-09-28T17:42:00Z");

    @Test
    public void theRecentPipelinesSurviveARestart() throws IOException {
        Path file = tempDir.resolve("data").resolve("prefs.json");
        Path a = Files.writeString(tempDir.resolve("a.json"), "{}");

        RecentPipelines saved = UiPreferences.updateRecents(file, r -> r.touch(a, T0));

        assertEquals(1, saved.entries().size());
        assertEquals(saved.entries(), UiPreferences.recents(file).entries());
    }

    @Test
    public void aMissingPrefsFileGivesTheDefaults() {
        Path file = tempDir.resolve("prefs.json");

        assertEquals(Optional.empty(), UiPreferences.savedLanguage(file));
        assertEquals(UiPreferences.ResourcesLayout.PROGRESS_LINE, UiPreferences.resourcesLayout(file));
        assertEquals(List.of(), UiPreferences.recents(file).entries());
        assertFalse(Files.exists(file), "reading never creates the file");
    }

    @Test
    public void aCorruptPrefsFileGivesTheDefaults() throws IOException {
        Path file = Files.writeString(tempDir.resolve("prefs.json"), "garbage {");

        assertEquals(Optional.empty(), UiPreferences.savedLanguage(file));
        assertEquals(UiPreferences.ResourcesLayout.PROGRESS_LINE, UiPreferences.resourcesLayout(file));
        assertEquals(List.of(), UiPreferences.recents(file).entries());
        assertEquals("garbage {", Files.readString(file), "reading leaves the file as it is");
    }

    @Test
    public void aFileThatCannotBeReadIsNeverReplacedByASave() throws IOException {
        // a directory at that path: reading fails on I/O, not as a missing file nor as a parse error
        Path file = Files.createDirectory(tempDir.resolve("prefs.json"));

        UiPreferences.saveLanguage(file, Locale.FRENCH);

        assertTrue(Files.isDirectory(file), "the save did not replace what it could not read");
        assertEquals(Optional.empty(), UiPreferences.savedLanguage(file));
        assertEquals(UiPreferences.ResourcesLayout.PROGRESS_LINE, UiPreferences.resourcesLayout(file));
    }

    @Test
    public void savingOnePreferenceKeepsTheOthers() throws IOException {
        Path file = tempDir.resolve("prefs.json");
        UiPreferences.saveLanguage(file, Locale.FRENCH);
        UiPreferences.saveResourcesLayout(file, UiPreferences.ResourcesLayout.SIDE_PANEL);

        assertEquals(Optional.of(Locale.FRENCH), UiPreferences.savedLanguage(file));
        assertEquals(UiPreferences.ResourcesLayout.SIDE_PANEL, UiPreferences.resourcesLayout(file));

        // another writer changes the language by hand: the next save keeps that change
        Files.writeString(file, Files.readString(file).replace("\"fr\"", "\"it\""));
        UiPreferences.saveResourcesLayout(file, UiPreferences.ResourcesLayout.PROGRESS_LINE);

        assertEquals(Optional.of(Locale.ITALIAN), UiPreferences.savedLanguage(file));
        assertEquals(UiPreferences.ResourcesLayout.PROGRESS_LINE, UiPreferences.resourcesLayout(file));
    }

    @Test
    public void anUnknownLayoutGivesTheDefault() throws IOException {
        Path file = Files.writeString(tempDir.resolve("prefs.json"), "{\"resourcesLayout\": \"OLD\"}");

        assertEquals(UiPreferences.ResourcesLayout.PROGRESS_LINE, UiPreferences.resourcesLayout(file));
    }

    @Test
    public void theUpdateCheckIsOnUnlessTurnedOff() {
        Path file = tempDir.resolve("prefs.json");
        assertTrue(UiPreferences.updateCheck(file));

        UiPreferences.saveUpdateCheck(file, false);

        assertFalse(UiPreferences.updateCheck(file));
        UiPreferences.saveUpdateCheck(file, true);
        assertTrue(UiPreferences.updateCheck(file));
    }

    @Test
    public void theLastUpdateCheckIsKeptToTheSecond() {
        Path file = tempDir.resolve("prefs.json");
        assertEquals(Optional.empty(), UiPreferences.updateLastCheck(file));

        UiPreferences.saveUpdateLastCheck(file, T0.plusMillis(900));

        assertEquals(Optional.of(T0), UiPreferences.updateLastCheck(file));
    }

    @Test
    public void theRecentsKeepTheMaximumWithoutASizeLimit() {
        Path file = tempDir.resolve("prefs.json");
        String longName = "x".repeat(1000);

        RecentPipelines saved = UiPreferences.updateRecents(file, r -> {
            for (int i = 0; i < RecentPipelines.MAX + 2; i++) {
                r.touch(tempDir.resolve(longName + i + ".json"), T0.plusSeconds(i));
            }
        });

        assertEquals(RecentPipelines.MAX, saved.entries().size());
        assertEquals(saved.entries(), UiPreferences.recents(file).entries());
    }
}
