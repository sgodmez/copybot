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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Reads and rewrites single keys of the configuration file, the other keys left as they are. */
public final class ConfigFiles {

    private static final String PLUGIN_PATH = "pluginPath";
    private static final String RESOURCES = "resources";
    private static final String RESOURCE_GROUPS = "resourceGroups";

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
        rewrite(configFile, json -> {
            if (pluginPath == null) {
                json.remove(PLUGIN_PATH);
            } else {
                json.addProperty(PLUGIN_PATH, pluginPath.toString());
            }
        });
    }

    /** The resource capacities and groups written in the file (empty when there are none). */
    public record Resources(Map<String, Integer> capacities, List<List<String>> groups) {
    }

    public static Resources readResources(Path configFile) {
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
     * Writes resources and resourceGroups (each one removed when empty) the way {@link #writePluginPath} writes
     * its key, the other keys left as they are.
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
        String original = readText(configFile);
        JsonObject json = parse(configFile, original);
        change.accept(json);
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
