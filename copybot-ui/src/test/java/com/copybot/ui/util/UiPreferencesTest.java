package com.copybot.ui.util;

import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.RecentPipelines.LastRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** The recents preference, on an in-memory node: the user's real preferences are never touched. */
public class UiPreferencesTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-09-28T17:42:00Z");

    @Test
    public void theTestsNeverReachTheRealUserRoot() {
        // surefire installs GuardedPreferencesFactory: any Preferences.userRoot()/userNodeForPackage lands there
        assertEquals(GuardedPreferencesFactory.class.getName(), System.getProperty("java.util.prefs.PreferencesFactory"));
        assertEquals(0, GuardedPreferencesFactory.userRootRequests(), "no test code asked for the user root");
    }

    @Test
    public void updateSavesAndReadsBack() throws IOException {
        Preferences node = new MemoryPreferences();
        Path a = Files.writeString(tempDir.resolve("a.json"), "{}");

        RecentPipelines saved = UiPreferences.updateRecents(node, r -> r.touch(a, T0));

        assertEquals(1, saved.entries().size());
        assertEquals(saved.entries(), UiPreferences.recents(node).entries());
    }

    @Test
    public void anEmptyOrGarbledNodeGivesNoRecents() {
        Preferences node = new MemoryPreferences();
        assertEquals(List.of(), UiPreferences.recents(node).entries());

        node.put(UiPreferences.RECENTS_KEY, "garbage {");
        assertEquals(List.of(), UiPreferences.recents(node).entries());
    }

    @Test
    public void theSavedValueFitsThePreferenceLimit() throws IOException {
        Preferences node = new MemoryPreferences();
        String longName = "x".repeat(1000); // ten entries of more than 1000 characters: beyond the limit
        RecentPipelines saved = UiPreferences.updateRecents(node, r -> {
            for (int i = 0; i < RecentPipelines.MAX; i++) {
                r.touch(tempDir.resolve(longName + i + ".json"), T0.plusSeconds(i));
                r.recordRun(tempDir.resolve(longName + i + ".json"), new LastRun(T0, PipelineStatus.SUCCESS, 1, 2, 3));
            }
        });

        String value = node.get(UiPreferences.RECENTS_KEY, null);
        assertNotNull(value);
        assertTrue(value.length() <= Preferences.MAX_VALUE_LENGTH);
        int kept = saved.entries().size();
        assertTrue(kept > 0 && kept < RecentPipelines.MAX, "the cut is forced: " + kept);
        assertEquals(UiPreferences.recents(node).entries(), saved.entries(), "the result is what was persisted");
        for (int i = 0; i < kept; i++) {
            assertEquals(longName + (RecentPipelines.MAX - 1 - i), saved.entries().get(i).displayName(),
                    "the most recent are kept, in order");
        }
    }

    /** A minimal in-memory node (no OS store). */
    static final class MemoryPreferences extends AbstractPreferences {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        MemoryPreferences() {
            super(null, "");
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return new MemoryPreferences();
        }

        @Override
        protected void syncSpi() throws BackingStoreException {
        }

        @Override
        protected void flushSpi() throws BackingStoreException {
        }
    }
}
