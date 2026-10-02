package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.copybot.utils.JsonTexts;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Reads and rewrites single keys of the configuration file, the other keys left as they are. */
public final class ConfigFiles {

    private static final String PLUGIN_PATH = "pluginPath";

    /** Writes "&amp;", "&lt;"... literally: a path is no HTML. */
    private static final Gson WRITER = GsonUtil.getGson().newBuilder().disableHtmlEscaping().create();

    private ConfigFiles() {
    }

    /** The pluginPath written in the file, empty when there is none. */
    public static Optional<Path> readPluginPath(Path configFile) {
        JsonElement value = read(configFile).get(PLUGIN_PATH);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw CopybotException.ofResource("config.plugin-path.not-string", configFile);
        }
        return Optional.of(Path.of(value.getAsString()));
    }

    /**
     * True when {@link #writePluginPath} would lose something of the file: it is not strict JSON (comments,
     * single quotes...) or repeats a key (only the last one is kept).
     */
    public static boolean rewriteLosesContent(Path configFile) {
        String text = readText(configFile);
        return !JsonTexts.isStrictJson(text) || !JsonTexts.duplicateKeys(text).isEmpty();
    }

    /**
     * Writes pluginPath (removes it when null), through a temporary file renamed over the configuration, with
     * the line endings of the original file. On failure the file is left as it was.
     */
    public static void writePluginPath(Path configFile, Path pluginPath) {
        String original = readText(configFile);
        JsonObject json = parse(configFile, original);
        if (pluginPath == null) {
            json.remove(PLUGIN_PATH);
        } else {
            json.addProperty(PLUGIN_PATH, pluginPath.toString());
        }
        String text = WRITER.toJson(json);
        if (original.contains("\r\n")) {
            text = text.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        Path temp = null;
        try {
            if (!Files.isWritable(configFile)) {
                throw new IOException(configFile + " is read-only");
            }
            temp = Files.createTempFile(configFile.toAbsolutePath().getParent(), "config", ".tmp");
            Files.writeString(temp, text);
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "config.write-failed", configFile, e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort: a leftover .tmp next to the configuration is harmless
                }
            }
        }
    }

    private static JsonObject read(Path configFile) {
        return parse(configFile, readText(configFile));
    }

    private static String readText(Path configFile) {
        try {
            return Files.readString(configFile);
        } catch (CharacterCodingException e) { // not UTF-8
            throw CopybotException.ofResource(e, "config.not-json", configFile);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "config.not-found", configFile.toAbsolutePath());
        }
    }

    private static JsonObject parse(Path configFile, String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw CopybotException.ofResource("config.not-json", configFile);
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw CopybotException.ofResource(e, "config.not-json", configFile);
        }
    }
}
