package com.copybot.ui.plugins;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.update.Release;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The texts of the Updates window that follow the ticks and the last check. */
public class UpdatesTextsTest {

    private static final Release RELEASE = new Release("0.7.0", URI.create("https://example.org/r"), "notes",
            List.of());

    private static PluginRow row(String code, String installed, String available, String engine) {
        PluginCatalog.Entry entry = new PluginCatalog.Entry("com.copybot.plugin." + code, code, available, engine,
                "v1", code + ".zip", Map.of(), Map.of(), null);
        return new PluginRow(entry, installed, List.of(Path.of("plugins", code)), PluginState.UP_TO_DATE);
    }

    private static final PluginRow COMPATIBLE = row("a", "1.0.0", "1.1.0", "1.0");
    private static final PluginRow OTHER = row("b", "1.0.0", "1.0.2", "1.0");
    private static final PluginRow LINKED = row("c", "1.0.0", "2.0.0", "2.0");
    private static final PluginRow CURRENT = row("d", "1.2.0", "1.2.0", "1.0");

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    private static UpdatesModel model(Optional<Release> release, PluginRow... rows) {
        return new UpdatesModel(release, List.of(rows), "1.3");
    }

    private static UpdatesModel.Plugin plugin(UpdatesModel model, PluginRow row) {
        return model.plugins().stream().filter(p -> p.row().equals(row)).findFirst().orElseThrow();
    }

    private static String join(String... parts) {
        return String.join(UpdatesTexts.SEPARATOR, parts);
    }

    @Test
    public void theSummaryNamesWhatIsTickedTheKnownSizeAndOneRestart() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE, OTHER);
        model.setAppSize(48L * 1024 * 1024);
        String restart = ResourcesEngine.getString("updates.summary.one-restart");

        assertEquals(join(ResourcesEngine.getString("updates.summary.app-and-plugins", 2),
                ResourcesEngine.getString("updates.summary.size", 48.0), restart), UpdatesTexts.summary(model));

        model.setChecked(COMPATIBLE, false);
        model.setChecked(OTHER, false);
        assertEquals(join(ResourcesEngine.getString("updates.summary.app"),
                ResourcesEngine.getString("updates.summary.size", 48.0), restart), UpdatesTexts.summary(model));

        // plugins alone: their archive size is not known, so no size at all
        model.setAppChecked(false);
        model.setChecked(OTHER, true);
        assertEquals(join(ResourcesEngine.getString("updates.summary.plugins", 1), restart),
                UpdatesTexts.summary(model));

        model.setChecked(OTHER, false);
        assertEquals(ResourcesEngine.getString("updates.nothing"), UpdatesTexts.summary(model));
    }

    @Test
    public void theSummaryHasNoSizeWhenNoneIsKnown() {
        UpdatesModel model = model(Optional.of(RELEASE));

        assertEquals(join(ResourcesEngine.getString("updates.summary.app"),
                ResourcesEngine.getString("updates.summary.one-restart")), UpdatesTexts.summary(model));
    }

    @Test
    public void theNoteOfALinkedPluginFollowsTheApplicationTick() {
        UpdatesModel model = model(Optional.of(RELEASE), COMPATIBLE, LINKED, CURRENT);

        assertEquals(ResourcesEngine.getString("updates.with-app", "0.7.0"), UpdatesTexts.note(model,
                plugin(model, LINKED)));
        model.setAppChecked(false);
        assertEquals(ResourcesEngine.getString("updates.needs-app", "0.7.0"), UpdatesTexts.note(model,
                plugin(model, LINKED)));
        assertEquals("", UpdatesTexts.note(model, plugin(model, COMPATIBLE)));
        assertEquals(ResourcesEngine.getString("updates.plugin-up-to-date"), UpdatesTexts.note(model,
                plugin(model, CURRENT)));

        UpdatesModel without = model(Optional.empty(), LINKED);
        assertEquals(ResourcesEngine.getString("updates.needs-newer-app"), UpdatesTexts.note(without,
                plugin(without, LINKED)));
    }

    @Test
    public void theLastCheckIsNeverTodayOrADate() {
        Instant now = Instant.parse("2026-10-26T15:00:00Z");

        assertEquals(ResourcesEngine.getString("updates.never-checked"),
                UpdatesTexts.lastCheck(Optional.empty(), now, ZoneOffset.UTC, Locale.ENGLISH));
        String today = UpdatesTexts.lastCheck(Optional.of(Instant.parse("2026-10-26T09:12:00Z")), now,
                ZoneOffset.UTC, Locale.ENGLISH);
        assertEquals(ResourcesEngine.getString("updates.checked-today", time(LocalTime.of(9, 12))), today);
        String before = UpdatesTexts.lastCheck(Optional.of(Instant.parse("2026-10-24T18:03:00Z")), now,
                ZoneOffset.UTC, Locale.ENGLISH);
        assertEquals(ResourcesEngine.getString("updates.checked-on", DateTimeFormatter.ofLocalizedDate(
                FormatStyle.MEDIUM).withLocale(Locale.ENGLISH).format(LocalDate.of(2026, 10, 24)),
                time(LocalTime.of(18, 3))), before);
    }

    private static String time(LocalTime time) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.ENGLISH).format(time);
    }

    @Test
    public void theHeaderIsTheVersionAndTheLastCheck() {
        assertEquals("Copybot 0.6.1" + UpdatesTexts.SEPARATOR + "never", UpdatesTexts.header("0.6.1", "never"));
    }
}
