package com.copybot.ui.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A GitHub release: its version (the tag without the leading {@code v}), the page of its notes and its files. */
public record Release(String version, URI notesUrl, List<Asset> assets) {

    /** A file of the release; {@code sha256} is lower-case hex without prefix, null when GitHub gave no digest. */
    public record Asset(String name, URI url, long size, String sha256) {
    }

    public Release {
        assets = List.copyOf(assets);
    }

    /** Reads the answer of {@code releases/latest}. */
    public static Release parse(String json) throws UpdateException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String tag = root.get("tag_name").getAsString();
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            URI notes = URI.create(root.get("html_url").getAsString());
            List<Asset> assets = new ArrayList<>();
            JsonElement array = root.get("assets");
            for (JsonElement element : array == null || array.isJsonNull() ? new JsonArray() : array.getAsJsonArray()) {
                JsonObject asset = element.getAsJsonObject();
                assets.add(new Asset(asset.get("name").getAsString(),
                        URI.create(asset.get("browser_download_url").getAsString()),
                        asset.get("size").getAsLong(), sha256(asset.get("digest"))));
            }
            return new Release(version, notes, assets);
        } catch (RuntimeException e) { // includes the JsonParseException of Gson
            // a malformed document, a missing field, a wrong type or a bad URI: the same for the user
            throw new UpdateException("update.error.unreadable");
        }
    }

    public Optional<Asset> asset(String name) {
        return assets.stream().filter(a -> a.name().equals(name)).findFirst();
    }

    private static String sha256(JsonElement digest) {
        if (digest == null || digest.isJsonNull()) {
            return null;
        }
        String value = digest.getAsString();
        return value.regionMatches(true, 0, "sha256:", 0, 7) ? value.substring(7).toLowerCase(Locale.ROOT) : null;
    }
}
