package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import com.copybot.utils.AtomicFiles;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The resume cursor of a pipeline, kept in a small human-editable JSON file next to the pipeline:
 * {@code { "cursor": { "date": "2026-09-28T15:42:10Z", "name": "DSC_4821.NEF" } }}.
 */
public final class ResumeStateStore {

    private static final String STATE_SUFFIX = ".state.json";

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
        JsonObject root = new JsonObject();
        root.add("cursor", c);
        try {
            AtomicFiles.write(stateFile, GsonUtil.getGson().toJson(root));
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "resume.state.write-error", stateFile.toAbsolutePath());
        }
    }
}
