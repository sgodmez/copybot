package com.copybot.ui.util;

import com.copybot.ui.model.RecentPipelines;
import com.copybot.utils.AtomicFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * User preferences persisted between launches, as a flat JSON object in the prefs.json of the data folder
 * ({@code {"language": "fr", "resourcesLayout": "SIDE_PANEL", "recentPipelines": {...}}}); a missing key is the
 * default. Until {@link #use(Path)} is called (the tests) the preferences stay in memory and nothing is written.
 */
public final class UiPreferences {

    private static final System.Logger LOG = System.getLogger(UiPreferences.class.getName());

    private static final String LANGUAGE_KEY = "language";
    private static final String RESOURCES_LAYOUT_KEY = "resourcesLayout";
    static final String RECENTS_KEY = "recentPipelines";
    private static final String UPDATE_CHECK_KEY = "updateCheck";
    private static final String UPDATE_LAST_CHECK_KEY = "updateLastCheck";
    private static final String UPDATE_LAST_CHECK_VERSION_KEY = "updateLastCheckVersion";

    /** The prefs.json in use; null until {@link #use(Path)}. */
    private static volatile Path file;
    /** What is kept while there is no file. */
    private static JsonObject memory = new JsonObject();

    private UiPreferences() {
    }

    /** Stores the preferences in that file from now on; called once, at startup. */
    public static void use(Path prefsFile) {
        file = prefsFile;
        resourcesLayout = null;
    }

    public static Optional<Locale> savedLanguage() {
        return savedLanguage(file);
    }

    public static void saveLanguage(Locale locale) {
        saveLanguage(file, locale);
    }

    static Optional<Locale> savedLanguage(Path prefsFile) {
        return string(read(prefsFile), LANGUAGE_KEY).map(Locale::forLanguageTag);
    }

    static void saveLanguage(Path prefsFile, Locale locale) {
        write(prefsFile, LANGUAGE_KEY, new JsonPrimitive(locale.toLanguageTag()));
    }

    /** Where the plan view shows the resources while a plan runs. */
    public enum ResourcesLayout {
        /** Compact gauges on the line of the progress bar (the default). */
        PROGRESS_LINE,
        /** A panel on the right of the table, always there so that nothing moves. */
        SIDE_PANEL
    }

    /** Read every second by the plan view: kept here rather than read from the file each time. */
    private static volatile ResourcesLayout resourcesLayout;

    public static ResourcesLayout resourcesLayout() {
        ResourcesLayout layout = resourcesLayout;
        if (layout == null) {
            layout = resourcesLayout(file);
            resourcesLayout = layout;
        }
        return layout;
    }

    public static void saveResourcesLayout(ResourcesLayout layout) {
        saveResourcesLayout(file, layout);
        resourcesLayout = layout;
    }

    static ResourcesLayout resourcesLayout(Path prefsFile) {
        try {
            return string(read(prefsFile), RESOURCES_LAYOUT_KEY).map(ResourcesLayout::valueOf)
                    .orElse(ResourcesLayout.PROGRESS_LINE);
        } catch (IllegalArgumentException e) {
            return ResourcesLayout.PROGRESS_LINE; // a value of another version
        }
    }

    static void saveResourcesLayout(Path prefsFile, ResourcesLayout layout) {
        write(prefsFile, RESOURCES_LAYOUT_KEY, new JsonPrimitive(layout.name()));
    }

    /** The recent pipelines and their last run (spec desktop-ui section 6); empty when none or unreadable. */
    public static RecentPipelines recents() {
        return recents(file);
    }

    /**
     * Reads the recent pipelines, applies the change and saves them (the oldest dropped if too long).
     *
     * @return what was persisted
     */
    public static RecentPipelines updateRecents(Consumer<RecentPipelines> change) {
        return updateRecents(file, change);
    }

    static RecentPipelines recents(Path prefsFile) {
        JsonElement value = read(prefsFile).get(RECENTS_KEY);
        return RecentPipelines.fromJson(value == null ? null : value.toString());
    }

    static RecentPipelines updateRecents(Path prefsFile, Consumer<RecentPipelines> change) {
        RecentPipelines recents = recents(prefsFile);
        change.accept(recents);
        String json = recents.toJson();
        write(prefsFile, RECENTS_KEY, JsonParser.parseString(json));
        return RecentPipelines.fromJson(json);
    }

    /** Whether Copybot looks for a new version at startup (the default). */
    public static boolean updateCheck() {
        return updateCheck(file);
    }

    public static void saveUpdateCheck(boolean enabled) {
        saveUpdateCheck(file, enabled);
    }

    /**
     * When this version last checked for a new version, successful or not; empty when never, or when the date was
     * recorded by another version (spec update-with-plugins §2: a new version checks at its first start, its plugins
     * may have updates the previous one could not take).
     */
    public static Optional<Instant> updateLastCheck(String version) {
        return updateLastCheck(file, version);
    }

    public static void saveUpdateLastCheck(Instant when, String version) {
        saveUpdateLastCheck(file, when, version);
    }

    static boolean updateCheck(Path prefsFile) {
        JsonElement value = read(prefsFile).get(UPDATE_CHECK_KEY);
        return value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()
                || value.getAsBoolean();
    }

    static void saveUpdateCheck(Path prefsFile, boolean enabled) {
        write(prefsFile, UPDATE_CHECK_KEY, new JsonPrimitive(enabled));
    }

    static Optional<Instant> updateLastCheck(Path prefsFile, String version) {
        JsonObject preferences = read(prefsFile);
        if (!string(preferences, UPDATE_LAST_CHECK_VERSION_KEY).equals(Optional.of(version))) {
            return Optional.empty();
        }
        JsonElement value = preferences.get(UPDATE_LAST_CHECK_KEY);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochSecond(value.getAsLong()));
        } catch (RuntimeException e) {
            return Optional.empty(); // a number out of range or not whole
        }
    }

    static void saveUpdateLastCheck(Path prefsFile, Instant when, String version) {
        write(prefsFile, UPDATE_LAST_CHECK_KEY, new JsonPrimitive(when.getEpochSecond()));
        write(prefsFile, UPDATE_LAST_CHECK_VERSION_KEY, new JsonPrimitive(version));
    }

    private static Optional<String> string(JsonObject preferences, String key) {
        JsonElement value = preferences.get(key);
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    /** The preferences of the file; empty when it is missing or invalid (the invalid one is logged, never touched). */
    private static synchronized JsonObject read(Path prefsFile) {
        JsonObject preferences = load(prefsFile);
        return preferences == null ? new JsonObject() : preferences;
    }

    /** Like {@link #read(Path)}, but null when the file exists and cannot be read (an I/O failure, not a parse error). */
    private static JsonObject load(Path prefsFile) {
        if (prefsFile == null) {
            return memory.deepCopy();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(prefsFile));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            LOG.log(System.Logger.Level.WARNING, "preferences ignored, not a JSON object: " + prefsFile);
        } catch (NoSuchFileException e) {
            // the first launch: the defaults
        } catch (JsonParseException e) {
            LOG.log(System.Logger.Level.WARNING, "preferences ignored, unreadable: " + prefsFile, e);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "preferences ignored, unreadable: " + prefsFile, e);
            return null;
        }
        return new JsonObject();
    }

    /**
     * Reads the file again, changes the key and writes everything: what another writer changed meanwhile is kept.
     * A file that cannot be read is left alone, a save would drop the other preferences.
     */
    private static synchronized void write(Path prefsFile, String key, JsonElement value) {
        JsonObject preferences = load(prefsFile);
        if (preferences == null) {
            LOG.log(System.Logger.Level.WARNING, "preferences not saved, the file cannot be read: " + prefsFile);
            return;
        }
        preferences.add(key, value);
        if (prefsFile == null) {
            memory = preferences;
            return;
        }
        try {
            Files.createDirectories(prefsFile.toAbsolutePath().getParent());
            AtomicFiles.write(prefsFile, preferences.toString());
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "preferences not saved in " + prefsFile, e);
        }
    }
}
