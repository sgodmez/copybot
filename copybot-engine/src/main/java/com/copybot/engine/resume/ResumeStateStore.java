package com.copybot.engine.resume;

import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            return Optional.of(new ItemKey(Instant.parse(c.get("date").getAsString()), c.get("name").getAsString()));
        } catch (IOException | JsonParseException | IllegalStateException | NullPointerException
                 | UnsupportedOperationException | DateTimeParseException e) {
            throw CopybotException.ofResource(e, "resume.state.invalid", stateFile.toAbsolutePath());
        }
    }

    /** Writes a temporary file in the same directory then moves it over the state file. */
    public void writeCursor(ItemKey cursor) {
        JsonObject c = new JsonObject();
        c.addProperty("date", cursor.date().toString());
        c.addProperty("name", cursor.name());
        JsonObject root = new JsonObject();
        root.add("cursor", c);

        Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.writeString(temp, GsonUtil.getGson().toJson(root));
            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort: the original error is the one worth reporting
            }
            throw CopybotException.ofResource(e, "resume.state.write-error", stateFile.toAbsolutePath());
        }
    }
}
