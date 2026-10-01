package com.copybot.ui.model;

import com.copybot.engine.pipeline.PipelineStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The recent pipelines of the home screen (spec desktop-ui §1): at most {@link #MAX} entries, the last
 * opened first, each with the summary of its last execution. Persisted as JSON in the UI preferences.
 * Not thread-safe: used from the JavaFX thread.
 */
public final class RecentPipelines {

    public static final int MAX = 10;

    /** The summary of an execution (spec desktop-ui §2, end of the run). */
    public record LastRun(Instant at, PipelineStatus status, int copied, int skipped, int errors) {
        public LastRun {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(status, "status");
        }
    }

    /** @param lastRun null when the pipeline was never executed from the UI */
    public record Entry(Path path, Instant openedAt, LastRun lastRun) {
        public Entry {
            path = normalize(path);
            Objects.requireNonNull(openedAt, "openedAt");
        }

        /** The file name without its extension. */
        public String displayName() {
            return RecentPipelines.displayName(path);
        }

        /** The pipeline file no longer exists: shown greyed, "not found" (spec desktop-ui §1). */
        public boolean isMissing() {
            return !Files.isRegularFile(path);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** The file name without its extension ("sd-card.json" gives "sd-card"). */
    public static String displayName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    /** The last opened first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Opened now: moved (or added) first, its last run kept; the oldest beyond {@link #MAX} are dropped. */
    public void touch(Path path, Instant now) {
        Path normalized = normalize(path);
        LastRun lastRun = find(normalized).map(Entry::lastRun).orElse(null);
        entries.removeIf(e -> e.path().equals(normalized));
        entries.addFirst(new Entry(normalized, now, lastRun));
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    /** Records the summary of an execution; a pipeline not in the list is added first. */
    public void recordRun(Path path, LastRun run) {
        Objects.requireNonNull(run, "run");
        Path normalized = normalize(path);
        if (find(normalized).isEmpty()) {
            touch(normalized, run.at());
        }
        entries.replaceAll(e -> e.path().equals(normalized) ? new Entry(normalized, e.openedAt(), run) : e);
    }

    public void remove(Path path) {
        Path normalized = normalize(path);
        entries.removeIf(e -> e.path().equals(normalized));
    }

    private Optional<Entry> find(Path normalized) {
        return entries.stream().filter(e -> e.path().equals(normalized)).findFirst();
    }

    // ---- persistence ----

    /**
     * Reads what {@link #toJson()} wrote; null, invalid JSON or invalid entries give what can be read. An
     * entry whose last run is unreadable is kept, without last run.
     */
    public static RecentPipelines fromJson(String json) {
        RecentPipelines recents = new RecentPipelines();
        if (json == null || json.isBlank()) {
            return recents;
        }
        JsonElement tree;
        try {
            tree = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            return recents;
        }
        if (!tree.isJsonArray()) {
            return recents;
        }
        for (JsonElement element : tree.getAsJsonArray()) {
            try {
                JsonObject object = element.getAsJsonObject();
                Path path = Path.of(object.get("path").getAsString());
                Instant openedAt = Instant.parse(object.get("openedAt").getAsString());
                LastRun lastRun = lastRun(object.get("lastRun"));
                if (recents.entries.size() < MAX && recents.find(normalize(path)).isEmpty()) {
                    recents.entries.add(new Entry(path, openedAt, lastRun));
                }
            } catch (RuntimeException e) { // IllegalState, ClassCast, NullPointer, InvalidPath, DateTimeParse...
                // an unreadable entry is dropped, the others are kept
            }
        }
        return recents;
    }

    /** The last run, null when absent, null or unreadable (bad date, unknown status...): the entry is kept. */
    private static LastRun lastRun(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject object = element.getAsJsonObject();
            return new LastRun(Instant.parse(object.get("at").getAsString()),
                    PipelineStatus.valueOf(object.get("status").getAsString()),
                    object.get("copied").getAsInt(), object.get("skipped").getAsInt(), object.get("errors").getAsInt());
        } catch (RuntimeException e) { // IllegalState, ClassCast, NullPointer, DateTimeParse, IllegalArgument...
            return null;
        }
    }

    public String toJson() {
        return toJson(entries);
    }

    /**
     * The JSON of the entries, the oldest dropped until it fits in maxChars (a preference value is
     * bounded, e.g. {@code Preferences.MAX_VALUE_LENGTH}).
     */
    public String toJsonWithin(int maxChars) {
        List<Entry> kept = new ArrayList<>(entries);
        String json = toJson(kept);
        while (json.length() > maxChars && !kept.isEmpty()) {
            kept.removeLast();
            json = toJson(kept);
        }
        return json;
    }

    private static String toJson(List<Entry> entries) {
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("path", entry.path().toString());
            object.addProperty("openedAt", entry.openedAt().toString());
            if (entry.lastRun() != null) {
                JsonObject run = new JsonObject();
                run.addProperty("at", entry.lastRun().at().toString());
                run.addProperty("status", entry.lastRun().status().name());
                run.addProperty("copied", entry.lastRun().copied());
                run.addProperty("skipped", entry.lastRun().skipped());
                run.addProperty("errors", entry.lastRun().errors());
                object.add("lastRun", run);
            }
            array.add(object);
        }
        return array.toString();
    }
}
