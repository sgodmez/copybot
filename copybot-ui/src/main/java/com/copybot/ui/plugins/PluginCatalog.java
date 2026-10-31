package com.copybot.ui.plugins;

import com.copybot.ui.update.UpdateException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The official plugins published with a release: the {@code catalogue.json} asset ({@code format: 1}). */
public final class PluginCatalog {

    /** A name that is safe as a folder, a tag or a file name: it cannot hold a separator nor be {@code ..}. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * A plugin of the catalogue.
     *
     * @param module      the name of its main module (the {@code plugin} value of the pipelines)
     * @param code        the plugin code, also the name of its folder in {@code plugins}
     * @param engine      major.minor of the engine it was built for
     * @param tag         the release that holds its archive
     * @param asset       the archive in that release
     * @param description by language ({@code en}, {@code fr}, {@code it})
     * @param changes     what this version changes, by language; empty when the catalogue says nothing
     * @param license     null when the plugin has none to show
     * @param requires    the official plugins it needs (its module {@code requires} their main module), empty
     *                    when none
     */
    public record Entry(String module, String code, String version, String engine, String tag, String asset,
                        Map<String, String> description, Map<String, String> changes, License license,
                        List<Requirement> requires) {

        public Entry {
            description = Map.copyOf(description);
            changes = Map.copyOf(changes);
            requires = List.copyOf(requires);
        }

        /** An entry that needs no other plugin. */
        public Entry(String module, String code, String version, String engine, String tag, String asset,
                     Map<String, String> description, Map<String, String> changes, License license) {
            this(module, code, version, engine, tag, asset, description, changes, license, List.of());
        }
    }

    /**
     * A plugin another one needs: its code and the lowest version that works (the version it was built against;
     * a version of the same major one at least as recent works too, as for the loader).
     */
    public record Requirement(String code, String version) {
    }

    /** A licence to show to the user: its name and its notice by language. */
    public record License(String name, Map<String, String> notice) {

        public License {
            notice = Map.copyOf(notice);
        }
    }

    private final List<Entry> entries;

    private PluginCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Reads a catalogue; an invalid entry is ignored, the others are kept. */
    public static PluginCatalog parse(String json) throws UpdateException {
        List<Entry> entries = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.get("format").getAsInt() != 1) {
                throw new UpdateException("update.error.unreadable");
            }
            for (JsonElement element : root.getAsJsonArray("plugins")) {
                try {
                    entries.add(entry(element.getAsJsonObject()));
                } catch (RuntimeException e) { // a missing or mistyped field, or a value that is not allowed
                    // this entry is ignored, the others stay available
                }
            }
        } catch (RuntimeException e) { // includes the JsonParseException of Gson
            throw new UpdateException("update.error.unreadable");
        }
        return new PluginCatalog(entries);
    }

    private static Entry entry(JsonObject o) {
        String code = text(o, "code");
        String tag = text(o, "tag");
        String asset = text(o, "asset");
        if (!SAFE_NAME.matcher(code).matches() || !SAFE_NAME.matcher(tag).matches()
                || !SAFE_NAME.matcher(asset).matches()) {
            throw new IllegalArgumentException("unsafe name");
        }
        return new Entry(text(o, "module"), code, text(o, "version"), text(o, "engine"), tag, asset,
                strings(o.get("description")), strings(o.get("changes")), license(o.get("license")),
                requires(o.get("requires")));
    }

    /** The plugins an entry needs; absent (a catalogue written before them): none. */
    private static List<Requirement> requires(JsonElement element) {
        List<Requirement> requires = new ArrayList<>();
        if (element != null && !element.isJsonNull()) {
            for (JsonElement item : element.getAsJsonArray()) {
                JsonObject o = item.getAsJsonObject();
                String code = text(o, "code");
                if (!SAFE_NAME.matcher(code).matches()) {
                    throw new IllegalArgumentException("unsafe name");
                }
                requires.add(new Requirement(code, text(o, "version")));
            }
        }
        return requires;
    }

    private static License license(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        return new License(text(o, "name"), strings(o.get("notice")));
    }

    private static String text(JsonObject o, String field) {
        String value = o.get(field).getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }

    private static Map<String, String> strings(JsonElement element) {
        Map<String, String> map = new LinkedHashMap<>();
        if (element != null && !element.isJsonNull()) {
            element.getAsJsonObject().asMap().forEach((k, v) -> map.put(k, v.getAsString()));
        }
        return map;
    }
}
