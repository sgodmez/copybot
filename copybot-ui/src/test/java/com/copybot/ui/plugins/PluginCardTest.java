package com.copybot.ui.plugins;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.report.ActionEntry;
import com.copybot.engine.plugin.report.ModuleEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.ui.plugins.PluginCard.Action;
import com.copybot.ui.plugins.PluginCard.ActionLine;
import com.copybot.ui.plugins.PluginCard.Filter;
import com.copybot.ui.plugins.PluginCard.Status;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PluginCardTest {

    private static final String GPS = "com.copybot.plugin.gpszone";
    private static final String META = "com.copybot.plugin.metadataextractor";
    private static final String OTHER = "com.example.other";

    private static PluginCatalog.Entry entry(String module, String code, String version) {
        return new PluginCatalog.Entry(module, code, version, "1.0", "v1", code + ".zip",
                Map.of("en", "About " + code), Map.of(), null);
    }

    private static PluginRow row(String module, String code, String version, String installed, PluginState state) {
        return new PluginRow(entry(module, code, version), installed,
                installed == null ? List.of() : List.of(Path.of("plugins", code)), state);
    }

    private static PluginEntry loaded(String module, String version, PluginSource source, PluginStatus status,
            String message, List<String> dependencies, List<String> missing, List<ActionEntry> actions) {
        return new PluginEntry(module, version, source, source == PluginSource.EMBEDDED ? null : Path.of("plugins", module),
                status, message, List.of(new ModuleEntry(module, version, null, true, false)), dependencies, missing,
                actions, null);
    }

    private static PluginEntry working(String module, String version, ActionEntry... actions) {
        return loaded(module, version, PluginSource.PLUGIN_PATH, PluginStatus.LOADED, null, List.of(), List.of(),
                List.of(actions));
    }

    private static PluginReport report(PluginEntry... entries) {
        return new PluginReport(Path.of("c.json"), Path.of("plugins"), List.of(), List.of(), List.of(entries));
    }

    private static PluginCard card(List<PluginCard> cards, String module) {
        return cards.stream().filter(c -> c.module().equals(module)).findFirst().orElseThrow();
    }

    @Test
    public void theOfficialPluginsComeFirstInCatalogueOrderThenTheOthersLoadedButNotTheEmbeddedOne() {
        PluginRow gps = row(GPS, "gps-zone", "1.0.1", "1.0.1", PluginState.UP_TO_DATE);
        PluginRow meta = row(META, "metadata-extractor", "1.0.2", null, PluginState.NOT_INSTALLED);
        PluginReport report = report(working(OTHER, "2.0.0"), working(GPS, "1.0.1"),
                loaded("embedded", null, PluginSource.EMBEDDED, PluginStatus.LOADED, null, List.of(), List.of(),
                        List.of(new ActionEntry(StepType.IN, "file.read", "Read files"))));

        List<PluginCard> cards = PluginCard.of(List.of(gps, meta), report);

        assertEquals(List.of(GPS, META, OTHER), cards.stream().map(PluginCard::module).toList());
        assertEquals(List.of("gps-zone", "metadata-extractor", OTHER), cards.stream().map(PluginCard::title).toList());
        assertTrue(cards.get(0).official());
        assertFalse(cards.get(2).official());
        assertEquals("1.0.1", cards.get(0).version());
        assertEquals("1.0.2", cards.get(1).version(), "not installed: the version of the catalogue");
        assertEquals("2.0.0", cards.get(2).version());
        assertEquals("About gps-zone", cards.get(0).row().entry().description().get("en"));

        PluginCard embedded = PluginCard.embedded(report).orElseThrow();
        assertEquals(List.of(new ActionLine(StepType.IN, "file.read", "Read files")), embedded.actions());
    }

    @Test
    public void everyActionIsALineWithItsTypeAndTheCodeAPipelineWrites() {
        PluginReport report = report(working(GPS, "1.0.1",
                new ActionEntry(StepType.ANALYZE, "check", "Check the time zone"),
                new ActionEntry(StepType.PROCESS, "fix", "fix")));

        PluginCard card = card(PluginCard.of(List.of(row(GPS, "gps-zone", "1.0.1", "1.0.1",
                PluginState.UP_TO_DATE)), report), GPS);

        assertEquals(List.of(new ActionLine(StepType.ANALYZE, "check", "Check the time zone"),
                new ActionLine(StepType.PROCESS, "fix", "fix")), card.actions());
        assertTrue(card.actions().get(0).named());
        assertFalse(card.actions().get(1).named(), "no translated name: the code alone");
    }

    @Test
    public void dependenciesMissingModulesAndLoadErrorsOnlyWhenThereAreSome() {
        PluginEntry failing = loaded(GPS, "1.0.1", PluginSource.PLUGIN_PATH, PluginStatus.ERROR, "It broke",
                List.of("metadata-extractor 1.0.0"), List.of("com.lib:2.0"), List.of());
        PluginRow gps = row(GPS, "gps-zone", "1.0.1", "1.0.1", PluginState.UP_TO_DATE);
        PluginRow meta = row(META, "metadata-extractor", "1.0.0", "1.0.0", PluginState.UP_TO_DATE);

        List<PluginCard> cards = PluginCard.of(List.of(gps, meta), report(failing, working(META, "1.0.0")));

        PluginCard broken = card(cards, GPS);
        assertEquals(List.of("metadata-extractor 1.0.0"), broken.dependsOn());
        assertEquals(List.of("com.lib:2.0"), broken.missing());
        assertEquals(List.of("It broke"), broken.loadErrors());
        assertEquals(Status.FAILED, broken.status(), "up to date in the catalogue, but it does not load");
        assertEquals(List.of(), broken.actions(), "the actions are known only for a loaded plugin");

        PluginCard fine = card(cards, META);
        assertEquals(List.of(), fine.dependsOn());
        assertEquals(List.of(), fine.missing());
        assertEquals(List.of(), fine.loadErrors());
        assertEquals(Status.UP_TO_DATE, fine.status());
    }

    @Test
    public void theFiltersSplitInstalledAndAvailable() {
        PluginRow installed = row(GPS, "gps-zone", "1.0.2", "1.0.1", PluginState.UPDATE_AVAILABLE);
        PluginRow available = row(META, "metadata-extractor", "1.0.0", null, PluginState.NOT_INSTALLED);
        PluginRow incompatible = row("com.copybot.plugin.x", "x", "3.0.0", null, PluginState.INCOMPATIBLE);

        List<PluginCard> cards = PluginCard.of(List.of(installed, available, incompatible),
                report(working(GPS, "1.0.1"), working(OTHER, "2.0.0")));

        assertEquals(4, cards.stream().filter(c -> c.shownBy(Filter.ALL)).count());
        assertEquals(List.of(GPS, OTHER), cards.stream().filter(c -> c.shownBy(Filter.INSTALLED))
                .map(PluginCard::module).toList());
        assertEquals(List.of(META, "com.copybot.plugin.x"), cards.stream().filter(c -> c.shownBy(Filter.AVAILABLE))
                .map(PluginCard::module).toList());
    }

    @Test
    public void aPluginOutsideTheCatalogueOffersNothing() {
        PluginCard other = card(PluginCard.of(List.of(), report(working(OTHER, "2.0.0"))), OTHER);

        assertFalse(other.official());
        assertNull(other.row());
        assertEquals(Status.LOADED, other.status());
        assertEquals(List.of(), other.offered(false));
        assertEquals(List.of(), other.offered(true));
    }

    @Test
    public void anUpdateLeadsToTheUpdatesWindowButARepairChosenFromAPipelineIsInstalledHere() {
        PluginRow update = row(GPS, "gps-zone", "1.0.2", "1.0.1", PluginState.UPDATE_AVAILABLE);
        PluginCard card = card(PluginCard.of(List.of(update), report(working(GPS, "1.0.1"))), GPS);

        assertEquals(Status.UPDATE_AVAILABLE, card.status());
        assertEquals(List.of(Action.OPEN_UPDATES, Action.REMOVE), card.offered(false));
        assertEquals(List.of(Action.UPDATE, Action.REMOVE), card.offered(true));
    }

    @Test
    public void theOtherStatesOfferWhatTheirRowOffers() {
        PluginRow available = row(META, "metadata-extractor", "1.0.0", null, PluginState.NOT_INSTALLED);
        PluginRow upToDate = row(GPS, "gps-zone", "1.0.1", "1.0.1", PluginState.UP_TO_DATE);
        PluginRow incompatible = row("com.copybot.plugin.x", "x", "3.0.0", null, PluginState.INCOMPATIBLE);

        List<PluginCard> cards = PluginCard.of(List.of(available, upToDate, incompatible),
                report(working(GPS, "1.0.1")));

        assertEquals(Status.AVAILABLE, cards.get(0).status());
        assertEquals(List.of(Action.INSTALL), cards.get(0).offered(false));
        assertEquals(List.of(Action.INSTALL), cards.get(0).offered(true));
        assertEquals(List.of(Action.REMOVE), cards.get(1).offered(false));
        assertEquals(Status.INCOMPATIBLE, cards.get(2).status());
        assertEquals(List.of(), cards.get(2).offered(false));
    }

    @Test
    public void thePipelineCodeOfAnActionIsTheActionOfTheStepAndThePluginItsModule() {
        PluginCard card = card(PluginCard.of(List.of(), report(working(GPS, "1.0.1",
                new ActionEntry(StepType.ANALYZE, "check", "Check")))), GPS);
        PluginCard embedded = PluginCard.embedded(report(loaded("embedded", null, PluginSource.EMBEDDED,
                PluginStatus.LOADED, null, List.of(), List.of(),
                List.of(new ActionEntry(StepType.IN, "file.read", "Read files"))))).orElseThrow();

        assertEquals(GPS, card.pipelinePlugin().orElseThrow(), "\"plugin\" of a step");
        assertTrue(embedded.pipelinePlugin().isEmpty(), "an embedded step leaves its \"plugin\" out");
    }
}
