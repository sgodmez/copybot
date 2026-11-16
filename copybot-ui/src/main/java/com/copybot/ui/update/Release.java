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
import java.util.regex.Pattern;

/**
 * A GitHub release: its version (the tag without the leading {@code v}), the page of its notes, the notes themselves
 * ({@code body} of the API, empty when none), its files, and whether GitHub marks it as a pre-release (a beta).
 */
public record Release(String version, URI notesUrl, String notes, List<Asset> assets, boolean prerelease) {

    /** The version of an application release: X.Y.Z, with an optional qualifier ({@code 1.3.0-beta.2}). */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?");

    /** A file of the release; {@code sha256} is lower-case hex without prefix, null when GitHub gave no digest. */
    public record Asset(String name, URI url, long size, String sha256) {
    }

    public Release {
        notes = notes == null ? "" : notes;
        assets = List.copyOf(assets);
    }

    /** A release that is not a pre-release. */
    public Release(String version, URI notesUrl, String notes, List<Asset> assets) {
        this(version, notesUrl, notes, assets, false);
    }

    /** A release without notes. */
    public Release(String version, URI notesUrl, List<Asset> assets) {
        this(version, notesUrl, "", assets);
    }

    /** Reads the answer of {@code releases/latest} (or of {@code releases/tags/<tag>}). */
    public static Release parse(String json) throws UpdateException {
        try {
            return read(JsonParser.parseString(json).getAsJsonObject());
        } catch (RuntimeException e) { // includes the JsonParseException of Gson
            // a malformed document, a missing field, a wrong type or a bad URI: the same for the user
            throw new UpdateException("update.error.unreadable");
        }
    }

    /**
     * Reads the answer of {@code releases} (a list, newest first by date) and keeps the highest version by
     * {@link AppVersion#compare}, pre-releases included: the release of the beta channel. Drafts (only visible to
     * the maintainers) and tags that are not an application version are skipped.
     *
     * @throws UpdateException {@code update.no-release} when no release is left
     */
    public static Release parseNewest(String json) throws UpdateException {
        Release newest = null;
        try {
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject root = element.getAsJsonObject();
                if (bool(root.get("draft"))) {
                    continue;
                }
                Release release = read(root);
                if (VERSION.matcher(release.version()).matches()
                        && (newest == null || AppVersion.compare(release.version(), newest.version()) > 0)) {
                    newest = release;
                }
            }
        } catch (RuntimeException e) { // includes the JsonParseException of Gson
            throw new UpdateException("update.error.unreadable");
        }
        if (newest == null) {
            throw new UpdateException("update.no-release");
        }
        return newest;
    }

    private static Release read(JsonObject root) {
        String tag = root.get("tag_name").getAsString();
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        URI notesUrl = URI.create(root.get("html_url").getAsString());
        List<Asset> assets = new ArrayList<>();
        JsonElement array = root.get("assets");
        for (JsonElement element : array == null || array.isJsonNull() ? new JsonArray() : array.getAsJsonArray()) {
            JsonObject asset = element.getAsJsonObject();
            assets.add(new Asset(asset.get("name").getAsString(),
                    URI.create(asset.get("browser_download_url").getAsString()),
                    asset.get("size").getAsLong(), sha256(asset.get("digest"))));
        }
        JsonElement body = root.get("body");
        return new Release(version, notesUrl, body == null || body.isJsonNull() ? "" : body.getAsString(), assets,
                bool(root.get("prerelease")));
    }

    private static boolean bool(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
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
