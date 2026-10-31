package com.copybot.ui.plugins;

import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.utils.VersionUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A plugin of the catalogue with what is installed.
 *
 * @param installedVersion the most recent installed version, null when none (or when it declares none)
 * @param installedFolders every folder of the plugins folder holding this module, whatever its name
 */
public record PluginRow(PluginCatalog.Entry entry, String installedVersion, List<Path> installedFolders,
                        PluginState state) {

    private static final String API_INCOMPATIBLE = "plugin.load.api-incompatible";

    public PluginRow {
        installedFolders = List.copyOf(installedFolders);
    }

    /**
     * One row per catalogue entry, in catalogue order, matched with the loaded plugins by module name.
     *
     * @param apiVersion the plugin API version of this Copybot (never the application version), compared with the
     *                   API version each entry was built for; null when unknown
     */
    public static List<PluginRow> of(PluginCatalog catalog, PluginReport report, String apiVersion) {
        List<PluginRow> rows = new ArrayList<>();
        for (PluginCatalog.Entry entry : catalog.entries()) {
            rows.add(row(entry, report, apiVersion));
        }
        return rows;
    }

    /** The number of rows with a compatible update. */
    public static long updatesAvailable(List<PluginRow> rows) {
        return rows.stream().filter(r -> r.state() == PluginState.UPDATE_AVAILABLE).count();
    }

    private static PluginRow row(PluginCatalog.Entry entry, PluginReport report, String apiVersion) {
        List<PluginEntry> found = report.plugins().stream()
                .filter(p -> p.name().equals(entry.module()) && p.source() != PluginSource.EMBEDDED).toList();
        List<Path> folders = found.stream().filter(p -> p.source() == PluginSource.PLUGIN_PATH && p.path() != null)
                .map(PluginEntry::path).distinct().toList();
        // an unknown API version (engine from the class path) refuses nothing, as the loader does
        boolean compatible = apiVersion == null || VersionUtil.isCompatible(apiVersion, entry.engine(), false);
        if (found.isEmpty()) {
            return new PluginRow(entry, null, folders, compatible ? PluginState.NOT_INSTALLED : PluginState.INCOMPATIBLE);
        }
        String installed = found.stream().map(PluginEntry::version).max(VersionUtil.VERSION_ORDER).orElse(null);
        boolean newer = VersionUtil.VERSION_ORDER.compare(installed, entry.version()) < 0;
        PluginState state;
        if (compatible && newer) {
            state = PluginState.UPDATE_AVAILABLE; // also what fixes a plugin refused for its API
        } else if (found.stream().anyMatch(PluginRow::refusedForItsApi)) {
            state = PluginState.INSTALLED_INCOMPATIBLE;
        } else {
            state = PluginState.UP_TO_DATE;
        }
        return new PluginRow(entry, installed, folders, state); // a working plugin is never shown incompatible
    }

    /**
     * Installed, and the version of the catalogue is newer but needs a newer engine (it is never offered): a working
     * plugin stays up to date for the actions, its row names the version waiting for a newer Copybot.
     */
    public boolean newerVersionNeedsANewerEngine() {
        return (state == PluginState.UP_TO_DATE || state == PluginState.INSTALLED_INCOMPATIBLE)
                && VersionUtil.VERSION_ORDER.compare(installedVersion, entry.version()) < 0;
    }

    /**
     * Installed, and the catalogue has a newer version, whatever plugin API it is built for: updated with the
     * application whose catalogue this is, every entry of which fits its plugin API (spec update-with-plugins §1).
     */
    public boolean updatedWithTheApplication() {
        return installedVersion != null && VersionUtil.VERSION_ORDER.compare(installedVersion, entry.version()) < 0;
    }

    /**
     * True when "Install" leads somewhere for a plugin a pipeline needs: it is not installed, or installed with a
     * compatible update (what fixes a plugin that failed to load); never for one installed without such an update.
     */
    public boolean offersInstall() {
        return state == PluginState.NOT_INSTALLED || state == PluginState.UPDATE_AVAILABLE;
    }

    /** The loader refused it with {@code plugin.load.api-incompatible}: recognised by its key, not its translation. */
    private static boolean refusedForItsApi(PluginEntry plugin) {
        return plugin.status() == PluginStatus.ERROR && API_INCOMPATIBLE.equals(plugin.errorKey());
    }
}
