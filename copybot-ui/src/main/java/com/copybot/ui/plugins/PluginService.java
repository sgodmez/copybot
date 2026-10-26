package com.copybot.ui.plugins;

import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.ui.update.Release;
import com.copybot.ui.update.UpdateApply;
import com.copybot.ui.update.UpdateCheck;
import com.copybot.ui.update.UpdateException;
import com.copybot.ui.update.UpdateService;
import com.copybot.utils.VersionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The official plugins without JavaFX (spec plugin-install §2-§6): the catalogue kept in memory, the rows compared
 * with the loaded plugins, the plugin updates counted along the check of the application, and the changes prepared
 * (archives downloaded and unpacked in the temporary folder of an update, manifest, script) up to the launch of the
 * script. It shares the guards of the application update ({@link UpdateService}): refused while busy or while another
 * Copybot runs from the same folder, unavailable outside the packaged release on Windows. The UI calls it off the
 * JavaFX thread and quits the application after {@link #launch}.
 */
public final class PluginService {

    private static final System.Logger LOG = System.getLogger(PluginService.class.getName());
    private static final String MANIFEST = "plugins.json";

    /** Where the catalogue and the archives come from ({@link CatalogService}). */
    public interface Catalogs {
        PluginCatalog fetch(Path dir) throws UpdateException, InterruptedException;

        Path download(PluginCatalog.Entry entry, Path dir, LongConsumer progress, BooleanSupplier cancelled)
                throws UpdateException, InterruptedException;
    }

    /** The progress of the downloads: archive {@code index} of {@code count}, {@code bytes} of it received so far. */
    public interface Progress {
        void update(int index, int count, String code, long bytes);
    }

    /**
     * What a check found.
     *
     * @param release       the newer release of Copybot, if any
     * @param pluginUpdates the number of installed official plugins with a compatible update (0 when the catalogue
     *                      could not be read)
     */
    public record Check(Optional<Release> release, long pluginUpdates) {
    }

    /** The keys of the application update that read differently for plugins. */
    private static final Map<String, String> KEYS = Map.of(
            "update.unavailable-dev", "plugins.unavailable-dev",
            "update.windows-only", "plugins.windows-only",
            "update.other-instance", "plugins.other-instance");

    private final UpdateService updates;
    private final Catalogs catalogs;
    private final Supplier<PluginReport> report;
    private final String engineVersion;
    /** The last catalogue read, null before. */
    private volatile PluginCatalog cached;

    /**
     * @param report        the plugins loaded (the report of the engine: it checks folders, call it off the JavaFX thread)
     * @param engineVersion the version of the engine, for the compatibility of the catalogue entries
     */
    public PluginService(UpdateService updates, Catalogs catalogs, Supplier<PluginReport> report, String engineVersion) {
        this.updates = updates;
        this.catalogs = catalogs;
        this.report = report;
        this.engineVersion = engineVersion;
    }

    /** The service of this application: the catalogue of GitHub, the version of the engine. */
    public static PluginService forThisApp(UpdateService updates, Supplier<PluginReport> report) {
        String version = updates.currentVersion();
        return new PluginService(updates, new CatalogService(UpdateCheck.httpClient(), CatalogService.LATEST,
                CatalogService.TAGS, version), report, VersionUtil.engineVersion().orElse(version));
    }

    // ---- the catalogue ----

    /** The catalogue already read this session. */
    public Optional<PluginCatalog> cached() {
        return Optional.ofNullable(cached);
    }

    /** The catalogue in memory, else read now. */
    public PluginCatalog catalog() throws UpdateException, InterruptedException {
        PluginCatalog catalog = cached;
        return catalog != null ? catalog : fetchCatalog();
    }

    /** Reads the catalogue of the latest release now (in a temporary folder deleted after) and keeps it. */
    public PluginCatalog fetchCatalog() throws UpdateException, InterruptedException {
        Path temp = updates.createTempDir();
        try {
            PluginCatalog catalog = catalogs.fetch(temp);
            cached = catalog;
            return catalog;
        } finally {
            UpdateService.deleteQuietly(temp);
        }
    }

    /** The rows of the catalogue with the plugins loaded now. */
    public List<PluginRow> rows(PluginCatalog catalog) {
        return rows(catalog, report.get());
    }

    public List<PluginRow> rows(PluginCatalog catalog, PluginReport loaded) {
        return PluginRow.of(catalog, loaded, engineVersion);
    }

    /** The rows of these modules (plugins a pipeline names that are not loaded), in catalogue order. */
    public List<PluginRow> rowsOf(Collection<String> modules, PluginCatalog catalog) {
        return rows(catalog).stream().filter(row -> modules.contains(row.entry().module())).toList();
    }

    /**
     * The entries of these modules (plugins a pipeline needs) the Plugins window offers to install: not installed, or
     * installed but fixed by a compatible update ({@link PluginRow#offersInstall}); in catalogue order.
     */
    public List<PluginCatalog.Entry> installable(Collection<String> modules, PluginCatalog catalog) {
        return rowsOf(modules, catalog).stream().filter(PluginRow::offersInstall).map(PluginRow::entry).toList();
    }

    // ---- the checks ----

    /**
     * The check at startup: when the application check is due (same setting, same date), the catalogue is read too
     * and the plugin updates are counted. Failures are only logged.
     */
    public Check automaticCheck() throws InterruptedException {
        if (!updates.checkDue()) {
            return new Check(Optional.empty(), 0);
        }
        Optional<Release> release = updates.automaticCheck();
        return new Check(release, quietUpdateCount());
    }

    /** The check of the preferences: a failure of the application check is thrown, one of the catalogue is logged. */
    public Check checkNow() throws UpdateException, InterruptedException {
        Optional<Release> release = updates.checkNow();
        return new Check(release, quietUpdateCount());
    }

    private long quietUpdateCount() throws InterruptedException {
        try {
            return PluginRow.updatesAvailable(rows(fetchCatalog()));
        } catch (UpdateException e) {
            LOG.log(System.Logger.Level.INFO, "plugin catalogue not read: {0} {1}", e.key(), List.of(e.args()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "plugin updates not counted", e);
        }
        return 0;
    }

    // ---- applying ----

    /** The message key telling why the changes cannot be applied now (not here, not now); empty when they can. */
    public Optional<String> applyBlocked() {
        return updates.installBlocked().map(PluginService::pluginKey);
    }

    /**
     * The message key refusing the changes while another Copybot runs from the same folder (its plugins are locked);
     * lists the processes of the system: called when the changes are asked for.
     */
    public Optional<String> otherInstance() {
        return updates.otherInstance().map(PluginService::pluginKey);
    }

    /**
     * True when Windows will ask for an administrator's approval: the {@code plugins} folder cannot be written, or the
     * application folder when there is no {@code plugins} folder yet (Copybot is shipped without one).
     */
    public boolean adminApprovalNeeded() {
        return updates.appDir().map(app -> {
            Path plugins = app.resolve("plugins");
            return !updates.writable(Files.isDirectory(plugins) ? plugins : app);
        }).orElse(false);
    }

    /**
     * Downloads and unpacks every archive to install directly in a new temporary folder of an update, writes the
     * manifest and the script there; nothing is changed in Copybot yet. On a refusal, a failure or a cancel, the
     * temporary folder is deleted.
     *
     * @param toInstallOrUpdate rows of the catalogue to install or update
     * @param toRemove          installed rows to uninstall
     */
    public UpdateService.Prepared prepare(List<PluginRow> toInstallOrUpdate, List<PluginRow> toRemove,
            Progress progress, BooleanSupplier cancelled) throws UpdateException, InterruptedException {
        Optional<String> blocked = updates.installBlocked().or(updates::otherInstance);
        if (blocked.isPresent()) {
            throw new UpdateException(pluginKey(blocked.get()));
        }
        Path plugins = updates.appDir().orElseThrow().resolve("plugins").toAbsolutePath().normalize();
        List<PluginRow> all = new ArrayList<>(toInstallOrUpdate);
        all.addAll(toRemove);
        for (PluginRow row : all) {
            for (Path folder : row.installedFolders()) {
                // the script refuses the rest anyway: told here, before anything is downloaded
                if (!plugins.equals(folder.toAbsolutePath().normalize().getParent())) {
                    throw new UpdateException("plugins.error.outside", folder);
                }
            }
        }
        Path temp = updates.createTempDir();
        boolean done = false;
        try {
            Map<PluginRow, Path> extracted = new LinkedHashMap<>();
            int count = toInstallOrUpdate.size();
            for (int i = 0; i < count; i++) {
                if (cancelled.getAsBoolean()) {
                    throw new UpdateException("update.error.cancelled");
                }
                PluginRow row = toInstallOrUpdate.get(i);
                String code = row.entry().code();
                int index = i;
                progress.update(index, count, code, 0);
                Path zip = catalogs.download(row.entry(), temp, bytes -> progress.update(index, count, code, bytes),
                        cancelled);
                try {
                    // directly in the temporary folder: the script installs nothing else
                    extracted.put(row, PluginArchive.extract(zip, temp, code));
                    Files.deleteIfExists(zip);
                } catch (IOException e) {
                    throw new UpdateException("update.error.zip", UpdateCheck.message(e));
                }
            }
            PluginChanges changes = PluginChanges.plan(toInstallOrUpdate, extracted, toRemove);
            UpdateService.Prepared prepared;
            try {
                Path manifest = temp.resolve(MANIFEST);
                changes.writeManifest(manifest);
                Path script = UpdateApply.writeScript(temp);
                prepared = new UpdateService.Prepared(temp, updates.pluginCommand(script, manifest));
            } catch (IOException e) {
                throw new UpdateException("update.error.io", UpdateCheck.message(e));
            }
            done = true;
            return prepared;
        } finally {
            if (!done) {
                UpdateService.deleteQuietly(temp);
            }
        }
    }

    /**
     * Starts the script unless cancelled meanwhile; the caller then quits the application. On a cancel or a failure
     * the temporary folder is deleted and Copybot goes on.
     */
    public void launch(UpdateService.Prepared prepared, BooleanSupplier cancelled) throws UpdateException {
        updates.launch(prepared, cancelled);
    }

    private static String pluginKey(String key) {
        return KEYS.getOrDefault(key, key);
    }
}
