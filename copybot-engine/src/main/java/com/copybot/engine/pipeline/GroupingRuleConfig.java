package com.copybot.engine.pipeline;

import com.copybot.plugin.api.grouping.GroupingRole;
import com.copybot.plugin.api.grouping.GroupingRule;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * One grouping rule: either a preset ({@code preset}: one of the core, or of the plugin whose module name is
 * {@code plugin}; expanded by the engine) or an own rule. {@code type} is sameBase, doubleExtension, folder or
 * regex. Roles are copy, read or ignore. {@code link} (regex only) is base or name (default name). For a regex rule
 * {@code main} is one expression, written as a string or a one-element array.
 */
public record GroupingRuleConfig(
        String preset,
        String plugin,
        String type,
        @JsonAdapter(StringOrList.class) List<String> main,
        Map<String, String> companions,
        String folder,
        String role,
        String companion,
        String link) {

    /** The pipeline form of a rule of a plugin preset: an own rule, with the same checks. */
    public static GroupingRuleConfig of(GroupingRule rule) {
        String type = switch (rule.type()) {
            case SAME_BASE -> "sameBase";
            case DOUBLE_EXTENSION -> "doubleExtension";
            case FOLDER -> "folder";
            case REGEX -> "regex";
        };
        Map<String, String> companions = null;
        if (rule.companions() != null) {
            companions = new LinkedHashMap<>();
            for (Map.Entry<String, GroupingRole> entry : rule.companions().entrySet()) {
                companions.put(entry.getKey(), lowerName(entry.getValue()));
            }
        }
        return new GroupingRuleConfig(null, null, type, rule.main(), companions, rule.folder(), lowerName(rule.role()),
                rule.companion(), lowerName(rule.link()));
    }

    private static String lowerName(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The expression of a regex rule's companion: each {@code \1}..{@code \9} of the template replaced by the quoted
     * text of that group (null: empty), the other escapes kept as they are.
     */
    public static String companionRegex(String template, IntFunction<String> groupText) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '\\' && i + 1 < template.length()) {
                char next = template.charAt(++i);
                if (next >= '1' && next <= '9') {
                    String text = groupText.apply(next - '0');
                    out.append(Pattern.quote(text == null ? "" : text));
                } else {
                    out.append(c).append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Reads a string as a one-element list, so the regex form {@code "main": "(.+)\\.mp4"} is accepted. */
    public static final class StringOrList extends TypeAdapter<List<String>> {

        @Override
        public void write(JsonWriter out, List<String> value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginArray();
            for (String item : value) {
                out.value(item);
            }
            out.endArray();
        }

        @Override
        public List<String> read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            List<String> items = new ArrayList<>();
            if (token == JsonToken.STRING) {
                items.add(in.nextString());
            } else if (token == JsonToken.BEGIN_ARRAY) {
                in.beginArray();
                while (in.hasNext()) {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                    } else {
                        items.add(in.nextString());
                    }
                }
                in.endArray();
            } else {
                throw new JsonParseException("a string or an array of strings is expected at " + in.getPath());
            }
            return items;
        }
    }
}
