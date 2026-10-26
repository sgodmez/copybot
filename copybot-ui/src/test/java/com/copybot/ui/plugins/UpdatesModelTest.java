package com.copybot.ui.plugins;

import com.copybot.ui.update.Release;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class UpdatesModelTest {

    private static final String API = "1.3";

    private static final Release RELEASE = new Release("0.7.0", URI.create("https://example.org/r"), "notes",
            List.of());

    private static PluginRow row(String code, String installed, String available, String engine) {
        PluginCatalog.Entry entry = new PluginCatalog.Entry("com.copybot.plugin." + code, code, available, engine,
                "v1", code + ".zip", Map.of(), Map.of(), null);
        return new PluginRow(entry, installed, List.of(Path.of("plugins", code)), PluginState.UP_TO_DATE);
    }

    private static final PluginRow COMPATIBLE = row("a", "1.0.0", "1.1.0", "1.0");
    private static final PluginRow LINKED = row("c", "1.0.0", "2.0.0", "2.0");
    private static final PluginRow CURRENT = row("d", "1.2.0", "1.2.0", "1.0");

    private static UpdatesModel model(Optional<Release> release, PluginRow... rows) {
        return new UpdatesModel(release, List.of(rows), API);
    }

    private static UpdatesModel.PluginLine line(UpdatesModel model, PluginRow row) {
        return model.plugins().stream().filter(p -> p.row().equals(row)).findFirst().orElseThrow().line();
    }

    @Test
    public void linesFollowTheVersionsAndTheApi() {
        UpdatesModel withRelease = model(Optional.of(RELEASE), COMPATIBLE, LINKED, CURRENT);
        UpdatesModel without = model(Optional.empty(), COMPATIBLE, LINKED, CURRENT);

        assertEquals(List.of(COMPATIBLE, LINKED, CURRENT),
                withRelease.plugins().stream().map(UpdatesModel.Plugin::row).toList());
        assertEquals(UpdatesModel.PluginLine.UPDATABLE, line(withRelease, COMPATIBLE));
        assertEquals(UpdatesModel.PluginLine.NEEDS_APP, line(withRelease, LINKED));
        assertEquals(UpdatesModel.PluginLine.UP_TO_DATE, line(withRelease, CURRENT));
        assertEquals(UpdatesModel.PluginLine.NEEDS_NEWER_APP, line(without, LINKED));
    }

    @Test
    public void everythingUpdatableIsCheckedAtTheStart() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE, LINKED, CURRENT);

        assertTrue(model.appChecked());
        assertTrue(model.checked(COMPATIBLE));
        assertTrue(model.checked(LINKED));
        assertFalse(model.checked(CURRENT));
        assertEquals(Set.of(COMPATIBLE.entry().module(), LINKED.entry().module()), model.checkedModules());
    }

    @Test
    public void withoutReleaseTheApplicationIsNotChecked() {
        UpdatesModel model = model(Optional.empty(), COMPATIBLE, LINKED);

        assertFalse(model.appChecked());
        assertFalse(model.checked(LINKED));
        assertFalse(model.enabled(LINKED));
        assertEquals(Set.of(COMPATIBLE.entry().module()), model.checkedModules());
        model.setAppChecked(true);
        assertFalse(model.appChecked());
        model.setChecked(LINKED, true);
        assertFalse(model.checked(LINKED));
    }

    @Test
    public void uncheckingTheApplicationUnchecksAndDisablesLinkedPlugins() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE, LINKED);

        model.setAppChecked(false);

        assertFalse(model.appChecked());
        assertFalse(model.checked(LINKED));
        assertFalse(model.enabled(LINKED));
        assertTrue(model.checked(COMPATIBLE));
        assertTrue(model.enabled(COMPATIBLE));
        assertEquals(Set.of(COMPATIBLE.entry().module()), model.checkedModules());
    }

    @Test
    public void checkingALinkedPluginChecksTheApplication() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE, LINKED);
        model.setAppChecked(false);

        model.setChecked(LINKED, true);

        assertTrue(model.appChecked());
        assertTrue(model.checked(LINKED));
        assertTrue(model.enabled(LINKED));
    }

    @Test
    public void aCompatiblePluginIsFreeOfTheApplication() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE, LINKED);

        model.setChecked(COMPATIBLE, false);
        assertTrue(model.appChecked());
        assertTrue(model.checked(LINKED));
        assertFalse(model.checked(COMPATIBLE));

        model.setAppChecked(false);
        model.setChecked(COMPATIBLE, true);
        assertFalse(model.appChecked());
        assertTrue(model.checked(COMPATIBLE));
    }

    @Test
    public void upToDatePluginsCannotBeChecked() {
        UpdatesModel model = model(Optional.of(RELEASE), CURRENT);

        model.setChecked(CURRENT, true);

        assertFalse(model.checked(CURRENT));
        assertFalse(model.enabled(CURRENT));
        assertTrue(model.checkedModules().isEmpty());
    }

    @Test
    public void nothingToApplyWhenNothingIsChecked() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE);
        assertFalse(model.nothingToApply());

        model.setAppChecked(false);
        assertFalse(model.nothingToApply());
        model.setChecked(COMPATIBLE, false);
        assertTrue(model.nothingToApply());
        assertTrue(model(Optional.empty(), CURRENT).nothingToApply());
    }

    @Test
    public void theKnownSizeIsTheApplicationOneWhileItIsChecked() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE);
        assertEquals(0, model.knownSize());

        model.setAppSize(48_000_000L);
        assertEquals(48_000_000L, model.knownSize());
        model.setAppChecked(false);
        assertEquals(0, model.knownSize());
    }
}
