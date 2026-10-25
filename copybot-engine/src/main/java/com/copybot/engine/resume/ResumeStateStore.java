package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.utils.AtomicFiles;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The resume state of a pipeline, kept in a small human-editable JSON file next to the pipeline:
 * {@code { "cursor": { "date": "2026-09-28T15:42:10Z", "name": "DSC_4821.NEF" },
 * "outPatterns": [ { "date": "2026-09-28T15:42:10Z", "name": "DSC_4821.NEF", "pattern": "..." } ] }}.
 * Each write replaces its own member and keeps the other.
 */
public final class ResumeStateStore {

    private static final String STATE_SUFFIX = ".state.json";
    private static final String OUT_PATTERNS = "outPatterns";

    private final Path stateFile;

    public ResumeStateStore(Path stateFile) {
        this.stateFile = stateFile;
    }

    /** {@code dir/sd-to-nas.json} -> {@code dir/sd-to-nas.state.json}. */
    public static ResumeStateStore forPipeline(Path pipelinePath) {
        String fileName = pipelinePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return new ResumeStateStore(pipelinePath.resolveSibling(baseName + STATE_SUFFIX));
    }

    public Path getPath() {
        return stateFile;
    }

    /**
     * @return the cursor, empty when the file does not exist or holds no cursor
     * @throws CopybotException when the file exists but cannot be understood: starting over silently
     *                          would re-import the whole card
     */
    public Optional<ItemKey> readCursor() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
            JsonElement cursor = root.get("cursor");
            if (cursor == null || cursor.isJsonNull()) {
                return Optional.empty();
            }
            JsonObject c = cursor.getAsJsonObject();
            return Optional.of(new ItemKey(Instant.parse(requiredString(c, "date")), requiredString(c, "name")));
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | DateTimeParseException e) {
            // IllegalStateException / UnsupportedOperationException: a JSON element of the wrong kind
            throw CopybotException.ofResource(e, "resume.state.invalid", stateFile.toAbsolutePath());
        }
    }

    /** @throws JsonParseException when the member is absent or null */
    private static String requiredString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException("missing \"" + member + "\"");
        }
        return value.getAsString();
    }

    /**
     * Writes the cursor through a temporary file of the same directory, flushed to the disk then moved over the
     * state file ({@link AtomicFiles}): a crash leaves the previous cursor or the new one.
     *
     * @throws CopybotException resume.state.write-error, the state file left as it was
     */
    public void writeCursor(ItemKey cursor) {
        JsonObject c = new JsonObject();
        c.addProperty("date", cursor.date().toString());
        c.addProperty("name", cursor.name());
        JsonObject root = existingRoot();
        root.add("cursor", c);
        write(root);
    }

    /**
     * The own output patterns chosen in the plan, by item (spec own-out-pattern §2).
     *
     * @return the patterns ordered by key, empty when the file does not exist or holds none
     * @throws CopybotException resume.state.invalid, like {@link #readCursor()}
     */
    public Map<ItemKey, String> readOutPatterns() {
        Map<ItemKey, String> patterns = new TreeMap<>();
        if (!Files.exists(stateFile)) {
            return patterns;
        }
        try {
            JsonElement member = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject().get(OUT_PATTERNS);
            if (member == null || member.isJsonNull()) {
                return patterns;
            }
            for (JsonElement element : member.getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                String pattern = requiredString(entry, "pattern");
                OutPattern.parse(pattern); // the file can be edited by hand: never refused at the write (spec own-out-pattern §1)
                patterns.put(new ItemKey(Instant.parse(requiredString(entry, "date")), requiredString(entry, "name")),
                        pattern);
            }
            return patterns;
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | DateTimeParseException | CopybotException e) {
            throw CopybotException.ofResource(e, "resume.state.invalid", stateFile.toAbsolutePath());
        }
    }

    /**
     * Replaces the whole {@code outPatterns} member, the cursor kept; an empty map removes the member.
     *
     * @throws CopybotException resume.state.write-error, the state file left as it was
     */
    public void writeOutPatterns(Map<ItemKey, String> patterns) {
        JsonObject root = existingRoot();
        if (patterns.isEmpty()) {
            root.remove(OUT_PATTERNS);
        } else {
            JsonArray array = new JsonArray();
            new TreeMap<>(patterns).forEach((key, pattern) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("date", key.date().toString());
                entry.addProperty("name", key.name());
                entry.addProperty("pattern", pattern);
                array.add(entry);
            });
            root.add(OUT_PATTERNS, array);
        }
        write(root);
    }

    /**
     * The current content, to replace one member of it; a missing file or a content that cannot be understood gives an
     * empty object.
     *
     * @throws CopybotException resume.state.write-error when the file cannot be read: writing would erase what it
     *                          keeps (the own out patterns), the file is left as it was
     */
    private JsonObject existingRoot() {
        String content;
        try {
            content = Files.readString(stateFile);
        } catch (NoSuchFileException e) {
            return new JsonObject();
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "resume.state.write-error", stateFile.toAbsolutePath());
        }
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return new JsonObject(); // nothing to keep: the write below replaces it
        }
    }

    private void write(JsonObject root) {
        try {
            AtomicFiles.write(stateFile, GsonUtil.getGson().toJson(root));
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "resume.state.write-error", stateFile.toAbsolutePath());
        }
    }
}
