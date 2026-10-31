package com.copybot.ui.plugins;

import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class PluginRowTest {

    private static final String MODULE = "com.copybot.plugin.metadataextractor";

    private static PluginCatalog catalog(String version, String api) throws Exception {
        return PluginCatalog.parse("{\"format\": 1, \"plugins\": [{\"module\": \"" + MODULE
                + "\", \"code\": \"metadata-extractor\", \"version\": \"" + version + "\", \"engine\": \"" + api
                + "\", \"tag\": \"v1\", \"asset\": \"a.zip\"}]}");
    }

    private static PluginEntry installed(String version, Path path, PluginStatus status, String message,
            String errorKey) {
        return new PluginEntry(MODULE, version, PluginSource.PLUGIN_PATH, path, status, message, List.of(), List.of(),
                List.of(), List.of(), errorKey);
    }

    private static PluginReport report(PluginEntry... entries) {
        return new PluginReport(Path.of("c.json"), Path.of("plugins"), List.of(), List.of(), List.of(entries));
    }

    private static PluginRow row(PluginCatalog catalog, PluginReport report, String api) {
        List<PluginRow> rows = PluginRow.of(catalog, report, api);
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    public void aPluginNotLoadedIsNotInstalled() throws Exception {
        PluginRow row = row(catalog("1.0.0", "1.0"), report(), "1.3");

        assertEquals(PluginState.NOT_INSTALLED, row.state());
        assertNull(row.installedVersion());
        assertTrue(row.installedFolders().isEmpty());
    }

    @Test
    public void aPluginAtTheCatalogueVersionIsUpToDate() throws Exception {
        PluginRow row = row(catalog("1.0.0", "1.0"), report(installed("1.0.0", Path.of("plugins/any"),
                PluginStatus.LOADED, null, null)), "1.3");

        assertEquals(PluginState.UP_TO_DATE, row.state());
        assertEquals("1.0.0", row.installedVersion());
        assertEquals(List.of(Path.of("plugins/any")), row.installedFolders());
    }

    @Test
    public void aNewerCatalogueVersionIsAnUpdateComparedNumerically() throws Exception {
        PluginReport report = report(installed("1.9.0", Path.of("plugins/a"), PluginStatus.LOADED, null, null));

        assertEquals(PluginState.UPDATE_AVAILABLE, row(catalog("1.10.0", "1.0"), report, "1.3").state());
    }

    @Test
    public void theMostRecentInstalledVersionCountsAndAllFoldersAreKept() throws Exception {
        PluginRow row = row(catalog("1.1.0", "1.0"), report(
                installed("1.1.0", Path.of("plugins/new"), PluginStatus.LOADED, null, null),
                installed("1.0.0", Path.of("plugins/old"), PluginStatus.IGNORED, "older", null)), "1.3");

        assertEquals(PluginState.UP_TO_DATE, row.state());
        assertEquals("1.1.0", row.installedVersion());
        assertEquals(2, row.installedFolders().size());
    }

    @Test
    public void anIncompatibleEntryIsNeverOffered() throws Exception {
        PluginCatalog catalog = catalog("2.0.0", "2.0"); // built for the API 2.0, this one is 1.3

        PluginRow notInstalled = row(catalog, report(), "1.3");
        assertEquals(PluginState.INCOMPATIBLE, notInstalled.state());

        PluginRow installedOne = row(catalog, report(installed("1.0.0", Path.of("plugins/a"), PluginStatus.LOADED,
                null, null)), "1.3"); // installed and working: see the next test
        assertNotEquals(PluginState.UPDATE_AVAILABLE, installedOne.state());
        assertFalse(PluginChoices.offered(installedOne).contains(PluginChoices.Action.UPDATE));

        assertEquals(0, PluginRow.updatesAvailable(List.of(notInstalled, installedOne)));
    }

    @Test
    public void aWorkingPluginWhoseNewestVersionNeedsANewerApiIsUpToDateAndSaysSo() throws Exception {
        PluginRow row = row(catalog("2.0.0", "2.0"), report(installed("1.0.0", Path.of("plugins/a"),
                PluginStatus.LOADED, null, null)), "1.3");

        assertEquals(PluginState.UP_TO_DATE, row.state()); // it works: never shown incompatible
        assertTrue(row.newerVersionNeedsANewerEngine());
        assertEquals(List.of(PluginChoices.Action.REMOVE), PluginChoices.offered(row));
        assertEquals(0, PluginRow.updatesAvailable(List.of(row)));
    }

    @Test
    public void aPluginAtTheCatalogueVersionOrWithACompatibleUpdateHasNoVersionWaitingForANewerApi()
            throws Exception {
        PluginEntry loaded = installed("1.0.0", Path.of("plugins/a"), PluginStatus.LOADED, null, null);

        assertFalse(row(catalog("1.0.0", "1.0"), report(loaded), "1.3").newerVersionNeedsANewerEngine());
        assertFalse(row(catalog("1.1.0", "1.0"), report(loaded), "1.3").newerVersionNeedsANewerEngine());
        assertFalse(row(catalog("2.0.0", "2.0"), report(), "1.3").newerVersionNeedsANewerEngine());
    }

    @Test
    public void aPluginRefusedForItsApiWithoutACompatibleUpdateIsInstalledButIncompatible() throws Exception {
        String message = ResourcesEngine.getString("plugin.load.api-incompatible", "2.0", "1.3");
        PluginRow row = row(catalog("2.0.0", "2.0"), report(installed("1.0.0", Path.of("plugins/a"),
                PluginStatus.ERROR, message, "plugin.load.api-incompatible")), "1.3");

        assertEquals(PluginState.INSTALLED_INCOMPATIBLE, row.state());
        assertTrue(row.newerVersionNeedsANewerEngine());
        assertEquals(0, PluginRow.updatesAvailable(List.of(row)));
    }

    @Test
    public void onlyANotInstalledPluginOrACompatibleUpdateOffersAnInstall() throws Exception {
        PluginEntry loaded = installed("1.0.0", Path.of("plugins/a"), PluginStatus.LOADED, null, null);
        PluginEntry refused = installed("1.0.0", Path.of("plugins/a"), PluginStatus.ERROR, "refused",
                "plugin.load.api-incompatible");

        assertTrue(row(catalog("1.0.0", "1.0"), report(), "1.3").offersInstall());
        assertTrue(row(catalog("1.1.0", "1.0"), report(refused), "1.3").offersInstall());
        assertFalse(row(catalog("1.0.0", "1.0"), report(refused), "1.3").offersInstall());
        assertFalse(row(catalog("1.0.0", "1.0"), report(loaded), "1.3").offersInstall());
        assertFalse(row(catalog("2.0.0", "2.0"), report(), "1.3").offersInstall());
    }

    @Test
    public void aMinorNewerThanTheApiIsIncompatible() throws Exception {
        assertEquals(PluginState.INCOMPATIBLE, row(catalog("1.0.0", "1.4"), report(), "1.3").state());
        assertEquals(PluginState.NOT_INSTALLED, row(catalog("1.0.0", "1.3"), report(), "1.3").state());
    }

    @Test
    public void anUnknownApiVersionRefusesNoEntry() throws Exception {
        // the engine runs from the class path (a development run): like the loader, nothing to compare with
        assertEquals(PluginState.NOT_INSTALLED, row(catalog("1.0.0", "2.0"), report(), null).state());
    }

    @Test
    public void aPluginRefusedForItsApiIsInstalledButIncompatible() throws Exception {
        String message = ResourcesEngine.getString("plugin.load.api-incompatible", "2.0", "1.3");
        PluginRow row = row(catalog("1.0.0", "1.0"), report(installed("1.0.0", Path.of("plugins/a"),
                PluginStatus.ERROR, message, "plugin.load.api-incompatible")), "1.3");

        assertEquals(PluginState.INSTALLED_INCOMPATIBLE, row.state());
    }

    @Test
    public void aRefusedPluginIsDetectedWhateverTheCurrentLanguage() throws Exception {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            String message = ResourcesEngine.getString("plugin.load.api-incompatible", "2.0", "1.3");
            PluginReport report = report(installed("1.0.0", Path.of("plugins/a"), PluginStatus.ERROR, message,
                    "plugin.load.api-incompatible")); // translated in English at load time
            Locale.setDefault(Locale.FRENCH); // the UI language changes afterwards, the report is not rebuilt

            assertEquals(PluginState.INSTALLED_INCOMPATIBLE, row(catalog("1.0.0", "1.0"), report, "1.3").state());
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    public void aCompatibleNewerVersionFixesAPluginRefusedForItsApi() throws Exception {
        String message = ResourcesEngine.getString("plugin.load.api-incompatible", "2.0", "1.3");
        PluginRow row = row(catalog("1.1.0", "1.0"), report(installed("1.0.0", Path.of("plugins/a"),
                PluginStatus.ERROR, message, "plugin.load.api-incompatible")), "1.3");

        assertEquals(PluginState.UPDATE_AVAILABLE, row.state());
    }

    @Test
    public void anotherErrorDoesNotMakeAPluginIncompatible() throws Exception {
        PluginRow row = row(catalog("1.0.0", "1.0"), report(installed("1.0.0", Path.of("plugins/a"),
                PluginStatus.ERROR, "some other failure", "plugin.load.no-module")), "1.3");

        assertEquals(PluginState.UP_TO_DATE, row.state());
    }

    @Test
    public void theEmbeddedPluginAndOtherModulesAreNotThisPlugin() throws Exception {
        PluginEntry embedded = new PluginEntry(MODULE, "1.3.0", PluginSource.EMBEDDED, null, PluginStatus.LOADED,
                null, List.of(), List.of(), List.of(), List.of(), null);
        PluginEntry other = new PluginEntry("other.module", "9.0.0", PluginSource.PLUGIN_PATH, Path.of("plugins/o"),
                PluginStatus.LOADED, null, List.of(), List.of(), List.of(), List.of(), null);

        assertEquals(PluginState.NOT_INSTALLED, row(catalog("1.0.0", "1.0"), report(embedded, other), "1.3").state());
    }

    @Test
    public void updatesAvailableCountsOnlyTheCompatibleUpdates() throws Exception {
        PluginReport report = report(installed("1.0.0", Path.of("plugins/a"), PluginStatus.LOADED, null, null));
        PluginRow update = row(catalog("1.1.0", "1.0"), report, "1.3");
        PluginRow upToDate = row(catalog("1.0.0", "1.0"), report, "1.3");

        assertEquals(PluginState.UPDATE_AVAILABLE, update.state());
        assertEquals(1, PluginRow.updatesAvailable(List.of(update, upToDate)));
    }
}
