package com.copybot.ui.plugins;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The plugins that other plugins need (ffmpeg needs exec): brought along, unavailable, broken by a removal. */
public class PluginDependenciesTest {

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    private static PluginRow row(String code, String installed, String available, PluginState state,
                                 PluginCatalog.Requirement... requires) {
        PluginCatalog.Entry entry = new PluginCatalog.Entry("com.copybot.plugin." + code, code, available, "1.2",
                "v1", code + ".zip", Map.of(), Map.of(), null, List.of(requires));
        return new PluginRow(entry, installed, installed == null ? List.of() : List.of(Path.of("plugins", code)),
                state);
    }

    private static PluginCatalog.Requirement needs(String code, String version) {
        return new PluginCatalog.Requirement(code, version);
    }

    private static final PluginRow FFMPEG = row("ffmpeg", null, "1.0.0", PluginState.NOT_INSTALLED,
            needs("exec", "2.0.0"));

    @Test
    public void aVersionMeetsARequirementOfTheSameMajorAtLeastAsRecent() {
        assertTrue(PluginDependencies.satisfies("2.0.0", "2.0.0"));
        assertTrue(PluginDependencies.satisfies("2.3.1", "2.0.0"));
        assertFalse(PluginDependencies.satisfies("2.0.0", "2.0.1"), "the revision counts");
        assertFalse(PluginDependencies.satisfies("1.0.0", "2.0.0"));
        assertFalse(PluginDependencies.satisfies("3.0.0", "2.0.0"), "another major version");
        assertFalse(PluginDependencies.satisfies(null, "2.0.0"), "not installed");
    }

    @Test
    public void aNeededPluginNotInstalledIsInstalledAlong() {
        PluginRow exec = row("exec", null, "2.0.0", PluginState.NOT_INSTALLED);

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(exec, FFMPEG), List.of(FFMPEG), List.of());

        assertEquals(List.of(FFMPEG, exec), plan.installOrUpdate());
        assertEquals(List.of(exec), plan.added());
        assertEquals(List.of(), plan.unavailable());
        assertEquals(List.of(), plan.broken());
    }

    @Test
    public void aNeededPluginTooOldIsUpdatedAlong() {
        PluginRow exec = row("exec", "1.0.0", "2.0.0", PluginState.UPDATE_AVAILABLE);

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(exec, FFMPEG), List.of(FFMPEG), List.of());

        assertEquals(List.of(FFMPEG, exec), plan.installOrUpdate());
        assertEquals(List.of(exec), plan.added());
    }

    @Test
    public void aNeededPluginInstalledInAVersionThatWorksIsLeftAlone() {
        PluginRow exec = row("exec", "2.1.0", "2.1.0", PluginState.UP_TO_DATE);

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(exec, FFMPEG), List.of(FFMPEG), List.of());

        assertEquals(List.of(FFMPEG), plan.installOrUpdate());
        assertEquals(List.of(), plan.added());
    }

    @Test
    public void aNeededPluginChosenAlreadyIsNotAddedTwice() {
        PluginRow exec = row("exec", null, "2.0.0", PluginState.NOT_INSTALLED);

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(exec, FFMPEG), List.of(exec, FFMPEG),
                List.of());

        assertEquals(List.of(exec, FFMPEG), plan.installOrUpdate());
        assertEquals(List.of(), plan.added());
    }

    @Test
    public void aRequirementTheCatalogueCannotMeetIsUnavailable() {
        PluginRow oldExec = row("exec", null, "1.0.0", PluginState.NOT_INSTALLED);
        PluginRow incompatible = row("exec", null, "2.0.0", PluginState.INCOMPATIBLE);

        for (List<PluginRow> rows : List.of(List.of(FFMPEG), List.of(oldExec, FFMPEG), List.of(incompatible, FFMPEG))) {
            PluginDependencies.Plan plan = PluginDependencies.plan(rows, List.of(FFMPEG), List.of());

            assertEquals(List.of(new PluginDependencies.Unavailable(FFMPEG, needs("exec", "2.0.0"))),
                    plan.unavailable(), rows.toString());
        }
    }

    @Test
    public void requirementsAreFollowedThroughThePluginsBroughtAlong() {
        PluginRow base = row("base", null, "1.0.0", PluginState.NOT_INSTALLED);
        PluginRow exec = row("exec", null, "2.0.0", PluginState.NOT_INSTALLED, needs("base", "1.0.0"));

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(base, exec, FFMPEG), List.of(FFMPEG),
                List.of());

        assertEquals(List.of(FFMPEG, exec, base), plan.installOrUpdate());
        assertEquals(List.of(exec, base), plan.added());
    }

    @Test
    public void removingAPluginOthersNeedNamesThem() {
        PluginRow exec = row("exec", "2.0.0", "2.0.0", PluginState.UP_TO_DATE);
        PluginRow ffmpeg = row("ffmpeg", "1.0.0", "1.0.0", PluginState.UP_TO_DATE, needs("exec", "2.0.0"));

        PluginDependencies.Plan alone = PluginDependencies.plan(List.of(exec, ffmpeg), List.of(), List.of(exec));
        PluginDependencies.Plan both = PluginDependencies.plan(List.of(exec, ffmpeg), List.of(), List.of(exec, ffmpeg));

        assertEquals(List.of(new PluginDependencies.Broken(ffmpeg, exec)), alone.broken());
        assertEquals(List.of(exec), alone.remove(), "warned, not refused");
        assertEquals(List.of(), both.broken());
    }

    @Test
    public void twoPluginsNeedingEachOtherAreInstalledOnce() {
        PluginRow a = row("a", null, "1.0.0", PluginState.NOT_INSTALLED, needs("b", "1.0.0"));
        PluginRow b = row("b", null, "1.0.0", PluginState.NOT_INSTALLED, needs("a", "1.0.0"));

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(a, b), List.of(a), List.of());

        assertEquals(List.of(a, b), plan.installOrUpdate());
        assertEquals(List.of(b), plan.added());
        assertEquals(List.of(), plan.unavailable());
    }

    @Test
    public void severalPluginsNeedingTheSamePluginBringItOnceAndAreAllNamedWhenItGoes() {
        PluginRow exec = row("exec", null, "2.0.0", PluginState.NOT_INSTALLED);
        PluginRow thumbs = row("thumbs", null, "1.0.0", PluginState.NOT_INSTALLED, needs("exec", "2.0.0"));

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(exec, FFMPEG, thumbs), List.of(FFMPEG, thumbs),
                List.of());
        assertEquals(List.of(FFMPEG, thumbs, exec), plan.installOrUpdate());
        assertEquals(List.of(exec), plan.added());
        assertEquals(List.of(ResourcesEngine.getString("plugins.confirm.install", "ffmpeg, thumbs"),
                ResourcesEngine.getString("plugins.confirm.with", "exec 2.0.0", "ffmpeg, thumbs")),
                PluginChangeTexts.changes(plan));

        PluginRow installedExec = row("exec", "2.0.0", "2.0.0", PluginState.UP_TO_DATE);
        PluginRow installedFfmpeg = row("ffmpeg", "1.0.0", "1.0.0", PluginState.UP_TO_DATE, needs("exec", "2.0.0"));
        PluginRow installedThumbs = row("thumbs", "1.0.0", "1.0.0", PluginState.UP_TO_DATE, needs("exec", "2.0.0"));
        PluginDependencies.Plan removal = PluginDependencies.plan(List.of(installedExec, installedFfmpeg,
                installedThumbs), List.of(), List.of(installedExec));
        assertEquals(List.of(new PluginDependencies.Broken(installedFfmpeg, installedExec),
                new PluginDependencies.Broken(installedThumbs, installedExec)), removal.broken());
    }

    @Test
    public void installingAPluginWhileRemovingOneItNeedsIsRefused() {
        PluginRow exec = row("exec", "2.0.0", "2.0.0", PluginState.UP_TO_DATE);

        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(exec, FFMPEG), List.of(FFMPEG), List.of(exec));

        assertEquals(List.of(new PluginDependencies.Unavailable(FFMPEG, needs("exec", "2.0.0"))), plan.neededRemoved());
        assertEquals(List.of(), plan.unavailable());
        assertEquals(List.of(ResourcesEngine.getString("plugins.confirm.needed-removed", "ffmpeg", "exec")),
                PluginChangeTexts.refusals(plan));
        assertEquals(List.of(), PluginChangeTexts.refusals(
                PluginDependencies.plan(List.of(exec, FFMPEG), List.of(FFMPEG), List.of())));
    }

    @Test
    public void theRefusalNamesARequirementTheCatalogueCannotMeet() {
        PluginDependencies.Plan plan = PluginDependencies.plan(List.of(FFMPEG), List.of(FFMPEG), List.of());

        assertEquals(List.of(ResourcesEngine.getString("plugins.confirm.unavailable", "ffmpeg", "exec", "2.0.0")),
                PluginChangeTexts.refusals(plan));
    }

    @Test
    public void theConfirmationNamesWhatComesAlongAndWhatBreaks() {
        PluginRow exec = row("exec", null, "2.0.0", PluginState.NOT_INSTALLED);
        List<String> install = PluginChangeTexts.changes(
                PluginDependencies.plan(List.of(exec, FFMPEG), List.of(FFMPEG), List.of()));

        assertEquals(List.of(ResourcesEngine.getString("plugins.confirm.install", "ffmpeg"),
                ResourcesEngine.getString("plugins.confirm.with", "exec 2.0.0", "ffmpeg")), install);

        PluginRow installedExec = row("exec", "2.0.0", "2.0.0", PluginState.UP_TO_DATE);
        PluginRow installedFfmpeg = row("ffmpeg", "1.0.0", "1.0.0", PluginState.UP_TO_DATE, needs("exec", "2.0.0"));
        List<String> removal = PluginChangeTexts.changes(PluginDependencies.plan(
                List.of(installedExec, installedFfmpeg), List.of(), List.of(installedExec)));

        assertEquals(List.of(ResourcesEngine.getString("plugins.confirm.remove", "exec"),
                ResourcesEngine.getString("plugins.confirm.needed-by", "exec", "ffmpeg")), removal);
    }

    @Test
    public void aMissingModuleOfTheCatalogueIsNamedAsItsPlugin() {
        PluginRow exec = row("exec", null, "2.0.0", PluginState.NOT_INSTALLED);

        assertEquals(ResourcesEngine.getString("plugins.card.missing-plugin", "exec", "2.0.0"),
                PluginChangeTexts.missing("com.copybot.plugin.exec:2.0.0", List.of(exec)));
        assertEquals("com.example.lib:1.0", PluginChangeTexts.missing("com.example.lib:1.0", List.of(exec)));
    }
}
