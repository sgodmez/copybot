package com.copybot.ui.plugins;

import com.copybot.ui.update.Download;
import com.copybot.ui.update.Release;
import com.copybot.ui.update.UpdateCheck;
import com.copybot.ui.update.UpdateException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Reads the catalogue of the latest release and downloads plugin archives, with the checks of the app update. */
public final class CatalogService implements PluginService.Catalogs {

    public static final URI LATEST = UpdateCheck.LATEST;
    public static final String TAGS = "https://api.github.com/repos/sgodmez/copybot/releases/tags/";

    static final String CATALOGUE = "catalogue.json";

    private final HttpClient client;
    private final URI latest;
    private final String tagsBase;
    private final String currentVersion;

    public CatalogService(HttpClient client, URI latest, String tagsBase, String currentVersion) {
        this.client = client;
        this.latest = latest;
        this.tagsBase = tagsBase;
        this.currentVersion = currentVersion;
    }

    /**
     * Downloads the catalogue of the latest release into {@code dir} (the caller's folder, which exists), reads it
     * and deletes the file.
     */
    public PluginCatalog fetch(Path dir) throws UpdateException, InterruptedException {
        return fetch(new UpdateCheck(client, latest, currentVersion).fetchLatest(), dir);
    }

    /** The catalogue of this release: the one an application update installs (spec update-with-plugins §1). */
    public PluginCatalog fetch(Release release, Path dir) throws UpdateException, InterruptedException {
        Release.Asset asset = release.asset(CATALOGUE).orElseThrow(() -> new UpdateException("plugins.error.no-catalogue"));
        Path file = Download.fetch(client, asset, dir, n -> { }, () -> false);
        try {
            return PluginCatalog.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) { // includes a file that is not UTF-8
            throw new UpdateException("update.error.unreadable");
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // nothing more to do, the caller deletes its folder
            }
        }
    }

    /**
     * Downloads the archive of the entry from the release of its tag; {@code dir/<asset name>}. A release of the tag
     * that is not found (404) names the tag, not "no release of Copybot yet".
     */
    public Path download(PluginCatalog.Entry entry, Path dir, LongConsumer progress, BooleanSupplier cancelled)
            throws UpdateException, InterruptedException {
        Release release;
        try {
            release = new UpdateCheck(client, URI.create(tagsBase + entry.tag()), currentVersion).fetchLatest();
        } catch (UpdateException e) {
            if (e.key().equals("update.no-release")) { // the 404 of UpdateCheck, here on releases/tags/<tag>
                throw new UpdateException("plugins.error.no-release", entry.tag(), entry.asset());
            }
            throw e;
        }
        Release.Asset asset = release.asset(entry.asset()).orElseThrow(() -> new UpdateException("plugins.error.no-asset", entry.asset()));
        return Download.fetch(client, asset, dir, progress, cancelled);
    }
}
