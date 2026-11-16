package com.copybot.engine.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A pipeline file written in YAML ({@code .yaml}, {@code .yml}): read into the same tree as its JSON form, so the
 * engine and the editor handle both alike, and written back as YAML. YAML 1.2 Core schema: {@code no}, {@code on},
 * {@code yes} are strings (not the booleans of YAML 1.1); a key written twice is refused (JSON keeps the last one,
 * silently); a value YAML would read as another type ({@code "true"}, {@code "123"}) is written quoted.
 */
public final class PipelineYaml {

    private PipelineYaml() {
    }

    /** By extension: {@code .yaml} or {@code .yml}, any case; any other file is JSON. */
    public static boolean isYaml(Path path) {
        Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /**
     * @return the tree, {@link JsonNull} for an empty document
     * @throws IllegalArgumentException invalid YAML, a key written twice, a value JSON cannot hold (.inf, binary...)
     */
    public static JsonElement read(String yaml) {
        Object value;
        try {
            value = new Load(loadSettings(false)).loadFromString(yaml);
        } catch (YamlEngineException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return toJson(value);
    }

    /** Whether the YAML holds comments, which a rewrite loses. False for an invalid YAML. */
    public static boolean hasComments(String yaml) {
        try {
            LoadSettings settings = loadSettings(true);
            ParserImpl parser = new ParserImpl(settings, new StreamReader(settings, yaml));
            while (parser.hasNext()) {
                if (parser.next().getEventId() == Event.ID.Comment) {
                    return true;
                }
            }
            return false;
        } catch (YamlEngineException e) {
            return false;
        }
    }

    /** Block-style YAML, ending with a line break. */
    public static String write(JsonElement tree) {
        DumpSettings settings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setSchema(new CoreSchema())
                .setSplitLines(false)
                .build();
        return new Dump(settings).dumpToString(toYaml(tree));
    }

    private static LoadSettings loadSettings(boolean parseComments) {
        return LoadSettings.builder()
                .setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false)
                .setParseComments(parseComments)
                .build();
    }

    private static JsonElement toJson(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case Map<?, ?> map -> {
                JsonObject object = new JsonObject();
                map.forEach((key, member) -> object.add(String.valueOf(key), toJson(member)));
                yield object;
            }
            case List<?> list -> {
                JsonArray array = new JsonArray();
                list.forEach(element -> array.add(toJson(element)));
                yield array;
            }
            case String string -> new JsonPrimitive(string);
            case Boolean bool -> new JsonPrimitive(bool);
            case Double number when number.isNaN() || number.isInfinite() ->
                    throw new IllegalArgumentException("not a JSON number: " + number);
            case Number number -> new JsonPrimitive(number);
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass().getSimpleName());
        };
    }

    private static Object toYaml(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            element.getAsJsonObject().entrySet().forEach(e -> map.put(e.getKey(), toYaml(e.getValue())));
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            element.getAsJsonArray().forEach(e -> list.add(toYaml(e)));
            return list;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            // a number read from a file keeps its text: an integer is written as an integer, so it reads back as one
            String text = primitive.getAsNumber().toString();
            if (text.matches("-?\\d+")) {
                BigInteger integer = new BigInteger(text);
                return integer.bitLength() < Long.SIZE ? (Object) integer.longValue() : integer;
            }
            return new BigDecimal(text).doubleValue();
        }
        return primitive.getAsString();
    }
}
