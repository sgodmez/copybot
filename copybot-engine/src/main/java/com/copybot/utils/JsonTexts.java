package com.copybot.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What a JSON text holds that a parse-then-rewrite would lose: lenient syntax, duplicate keys. */
public final class JsonTexts {

    private static final TypeAdapter<JsonElement> STRICT_READER = new Gson().getAdapter(JsonElement.class);

    private JsonTexts() {
    }

    /** The text is one strict (RFC 8259) JSON value: no comment, single quote, unquoted name... */
    public static boolean isStrictJson(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            STRICT_READER.read(reader);
            return reader.peek() == JsonToken.END_DOCUMENT;
        } catch (IOException | RuntimeException e) { // MalformedJsonException is an IOException
            return false;
        }
    }

    /**
     * The paths of the members repeated in an object (at any level), the text read leniently; what was read
     * before a syntax error.
     */
    public static List<String> duplicateKeys(String json) {
        List<String> duplicates = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.LENIENT);
            collectDuplicateKeys(reader, duplicates);
        } catch (IOException | RuntimeException e) {
            // not JSON: the caller's parse reports it, only a warning is lost
        }
        return duplicates;
    }

    private static void collectDuplicateKeys(JsonReader reader, List<String> duplicates) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) {
                        duplicates.add(reader.getPath().replaceFirst("^\\$\\.", ""));
                    }
                    collectDuplicateKeys(reader, duplicates);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) {
                    collectDuplicateKeys(reader, duplicates);
                }
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }
}
