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

    /** A plugin whose next version needs another plugin at this version at least. */
    private static PluginRow needing(String code, String installed, String available, String engine,
                                     String neededCode, String neededVersion) {
        PluginCatalog.Entry entry = new PluginCatalog.Entry("com.copybot.plugin." + code, code, available, engine,
                "v1", code + ".zip", Map.of(), Map.of(), null,
                List.of(new PluginCatalog.Requirement(neededCode, neededVersion)));
        return new PluginRow(entry, installed, List.of(Path.of("plugins", code)), PluginState.UP_TO_DATE);
    }

    @Test
    public void anUpdateNeedingANewerPluginTicksItsUpdateAndGoesWithIt() {
        PluginRow exec = row("exec", "2.0.0", "2.1.0", "1.0");
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "2.1.0");
        UpdatesModel model = model(Optional.empty(), exec, ffmpeg);
        assertTrue(model.checked(exec));
        assertTrue(model.checked(ffmpeg));

        model.setChecked(exec, false);
        assertFalse(model.checked(ffmpeg), "it would not load with exec 2.0.0");

        model.setChecked(ffmpeg, true);
        assertTrue(model.checked(exec), "ticked along");
        assertTrue(model.checked(ffmpeg));
    }

    @Test
    public void anUpdateWhoseRequirementIsAlreadyMetIsFree() {
        PluginRow exec = row("exec", "2.1.0", "2.2.0", "1.0");
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "2.1.0");
        UpdatesModel model = model(Optional.empty(), exec, ffmpeg);

        model.setChecked(exec, false);
        assertTrue(model.checked(ffmpeg));
        model.setChecked(ffmpeg, false);
        model.setChecked(ffmpeg, true);
        assertFalse(model.checked(exec));
    }

    @Test
    public void anUpdateNeedingAPluginThatTravelsWithTheApplicationFollowsIt() {
        PluginRow exec = row("exec", "2.0.0", "3.0.0", "2.0"); // linked: built for the next plugin API
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "3.0.0");

        UpdatesModel without = model(Optional.empty(), exec, ffmpeg);
        assertFalse(without.checked(ffmpeg), "exec 3 cannot come: ffmpeg 1.1 is not offered alone");
        without.setChecked(ffmpeg, true);
        assertFalse(without.checked(ffmpeg));

        UpdatesModel with = model(Optional.of(RELEASE), exec, ffmpeg);
        assertTrue(with.checked(ffmpeg));
        with.setAppChecked(false);
        assertFalse(with.checked(exec));
        assertFalse(with.checked(ffmpeg));
        with.setChecked(ffmpeg, true);
        assertTrue(with.appChecked());
        assertTrue(with.checked(exec));
    }

    @Test
    public void aNeededUpdateIsTickedOnlyWhenItsVersionMeetsTheRequirement() {
        PluginRow exec = row("exec", "2.0.0", "2.0.5", "1.0"); // updatable, but not to the version needed
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "2.1.0");
        UpdatesModel model = model(Optional.empty(), exec, ffmpeg);

        assertTrue(model.checked(exec));
        assertFalse(model.checked(ffmpeg), "exec 2.0.5 does not meet 2.1.0");
        model.setChecked(exec, false);
        model.setChecked(ffmpeg, true);
        assertFalse(model.checked(exec), "not ticked for nothing");
        assertFalse(model.checked(ffmpeg));
        assertFalse(model.enabled(ffmpeg));
    }

    private static PluginRow notInstalled(String code, String available, String engine, PluginState state) {
        PluginCatalog.Entry entry = new PluginCatalog.Entry("com.copybot.plugin." + code, code, available, engine,
                "v1", code + ".zip", Map.of(), Map.of(), null);
        return new PluginRow(entry, null, List.of(), state);
    }

    @Test
    public void aNeededPluginNotInstalledIsTickedForInstallWithThePluginThatNeedsIt() {
        PluginRow exec = notInstalled("exec", "2.0.0", "1.0", PluginState.NOT_INSTALLED);
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "2.0.0");
        PluginRow other = notInstalled("other", "1.0.0", "1.0", PluginState.NOT_INSTALLED);
        UpdatesModel model = new UpdatesModel(Optional.empty(), List.of(ffmpeg), List.of(exec, ffmpeg, other), API);

        assertEquals(List.of(ffmpeg, exec), model.plugins().stream().map(UpdatesModel.Plugin::row).toList(),
                "a line for the needed plugin, none for the others");
        UpdatesModel.Plugin execLine = model.plugins().get(1);
        assertTrue(execLine.install());
        assertEquals(UpdatesModel.PluginLine.UPDATABLE, execLine.line());
        assertTrue(model.checked(ffmpeg));
        assertTrue(model.checked(exec), "installed along");
        assertFalse(model.enabled(exec), "it follows the plugin that needs it");
        assertEquals(Set.of(ffmpeg.entry().module(), exec.entry().module()), model.checkedModules());

        model.setChecked(ffmpeg, false);
        assertFalse(model.checked(exec), "no longer needed");
        model.setChecked(exec, true);
        assertFalse(model.checked(exec), "never ticked alone");
        model.setChecked(ffmpeg, true);
        assertTrue(model.checked(exec));
    }

    @Test
    public void aNeededPluginNotInstalledThatCannotBeInstalledLeavesTheUpdateUnticked() {
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "2.0.0");
        PluginRow oldExec = notInstalled("exec", "1.0.0", "1.0", PluginState.NOT_INSTALLED);
        PluginRow otherApi = notInstalled("exec", "2.0.0", "2.0", PluginState.INCOMPATIBLE);
        PluginCatalog.Requirement execTwo = new PluginCatalog.Requirement("exec", "2.0.0");

        for (List<PluginRow> catalogue : List.of(List.of(ffmpeg), List.of(oldExec, ffmpeg), List.of(otherApi, ffmpeg))) {
            UpdatesModel model = new UpdatesModel(Optional.empty(), List.of(ffmpeg), catalogue, API);

            assertFalse(model.checked(ffmpeg), catalogue.toString());
            assertFalse(model.enabled(ffmpeg), catalogue.toString());
            assertEquals(Optional.of(new UpdatesModel.Waiting(execTwo, catalogue.getFirst() == otherApi)),
                    model.waitingFor(ffmpeg), catalogue.toString());
        }

        // with the offered Copybot, the plugin built for its API comes with it
        UpdatesModel with = new UpdatesModel(Optional.of(RELEASE), List.of(ffmpeg), List.of(otherApi, ffmpeg), API);
        assertTrue(with.checked(ffmpeg));
        assertTrue(with.checked(otherApi));
        with.setAppChecked(false);
        assertFalse(with.checked(ffmpeg));
        assertFalse(with.checked(otherApi));
    }

    @Test
    public void anUpdateWaitingForAPluginThatNeedsANewerCopybotSaysSo() {
        PluginRow exec = row("exec", "2.0.0", "3.0.0", "2.0");
        PluginRow ffmpeg = needing("ffmpeg", "1.0.0", "1.1.0", "1.0", "exec", "3.0.0");
        UpdatesModel model = model(Optional.empty(), exec, ffmpeg);

        assertEquals(UpdatesModel.PluginLine.NEEDS_NEWER_APP, line(model, exec));
        assertEquals(Optional.of(new UpdatesModel.Waiting(new PluginCatalog.Requirement("exec", "3.0.0"), true)),
                model.waitingFor(ffmpeg));
        assertEquals(Optional.empty(), model.waitingFor(exec));
        assertEquals(Optional.empty(), model(Optional.of(RELEASE), exec, ffmpeg).waitingFor(ffmpeg),
                "with the offered Copybot it can come");
    }

    @Test
    public void twoPluginsNeedingEachOtherAreTickedTogether() {
        PluginRow a = needing("a", "1.0.0", "2.0.0", "1.0", "b", "2.0.0");
        PluginRow b = needing("b", "1.0.0", "2.0.0", "1.0", "a", "2.0.0");
        UpdatesModel model = model(Optional.empty(), a, b);
        assertTrue(model.checked(a));
        assertTrue(model.checked(b));

        model.setChecked(a, false);
        assertFalse(model.checked(b));
        model.setChecked(b, true);
        assertTrue(model.checked(a));
    }
}
