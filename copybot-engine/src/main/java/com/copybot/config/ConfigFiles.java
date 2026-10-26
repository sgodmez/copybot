package com.copybot.config;

import com.copybot.exception.CopybotException;
import com.copybot.utils.AtomicFiles;
import com.copybot.utils.GsonUtil;
import com.copybot.utils.JsonTexts;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Reads and rewrites single keys of the configuration file, the other keys left as they are. */
public final class ConfigFiles {

    private static final String RESOURCES = "resources";
    private static final String RESOURCE_GROUPS = "resourceGroups";

    /** Writes "&amp;", "&lt;"... literally: a path is no HTML. */
    private static final Gson WRITER = GsonUtil.getGson().newBuilder().disableHtmlEscaping().create();

    private ConfigFiles() {
    }

    /**
     * True when {@link #writeResources} would lose something of the file: it is not strict JSON (comments,
     * single quotes...) or repeats a key (only the last one is kept). A missing file loses nothing.
     */
    public static boolean rewriteLosesContent(Path configFile) {
        if (Files.notExists(configFile)) {
            return false;
        }
        String text = readText(configFile);
        return !JsonTexts.isStrictJson(text) || !JsonTexts.duplicateKeys(text).isEmpty();
    }

    /** The resource capacities and groups written in the file (empty when there are none). */
    public record Resources(Map<String, Integer> capacities, List<List<String>> groups) {
    }

    /** A missing file holds no resources. */
    public static Resources readResources(Path configFile) {
        if (Files.notExists(configFile)) {
            return new Resources(Map.of(), List.of());
        }
        CopybotConfig config;
        try {
            config = GsonUtil.getGson().fromJson(readText(configFile), CopybotConfig.class);
        } catch (JsonParseException e) {
            throw CopybotException.ofResource(e, "config.not-json", configFile);
        }
        if (config == null) {
            throw CopybotException.ofResource("config.not-json", configFile);
        }
        Map<String, Integer> capacities = config.resources() != null ? config.resources() : Map.of();
        List<List<String>> groups = config.resourceGroups() != null ? config.resourceGroups() : List.of();
        return new Resources(new LinkedHashMap<>(capacities), groups.stream().map(List::copyOf).toList());
    }

    /**
     * Writes resources and resourceGroups (each one removed when empty) through a temporary file renamed over the
     * configuration, with the line endings of the original file, the other keys left as they are. A missing file is
     * created (with its folder), holding only the non-empty keys. On failure the file is left as it was.
     */
    public static void writeResources(Path configFile, Resources resources) {
        rewrite(configFile, json -> {
            if (resources.capacities().isEmpty()) {
                json.remove(RESOURCES);
            } else {
                json.add(RESOURCES, WRITER.toJsonTree(resources.capacities()));
            }
            if (resources.groups().isEmpty()) {
                json.remove(RESOURCE_GROUPS);
            } else {
                json.add(RESOURCE_GROUPS, WRITER.toJsonTree(resources.groups()));
            }
        });
    }

    private static void rewrite(Path configFile, Consumer<JsonObject> change) {
        boolean exists = Files.exists(configFile);
        String original = exists ? readText(configFile) : "{}";
        JsonObject json = parse(configFile, original);
        change.accept(json);
        String text = WRITER.toJson(json);
        if (original.contains("\r\n")) {
            text = text.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        try {
            if (exists && !Files.isWritable(configFile)) {
                throw new IOException(configFile + " is read-only");
            }
            Path folder = configFile.toAbsolutePath().getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            AtomicFiles.write(configFile, text); // flushed to the disk, then moved over the configuration
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "config.write-failed", configFile, e.getMessage());
        }
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
