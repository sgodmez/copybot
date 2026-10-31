package com.copybot.ui.plugins;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.update.Release;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The texts of the Updates window that depend on its state (the last check, the note of a plugin line, the summary
 * above the buttons), kept out of the controller so that they can be tested without JavaFX.
 */
public final class UpdatesTexts {

    /** Between the parts of the header and of the summary. */
    static final String SEPARATOR = " \u00b7 ";

    private static final double MEGABYTE = 1024 * 1024;

    private UpdatesTexts() {
    }

    /** "checked today at 09:12", "checked on 24 Oct 2026 at 18:03", or "never checked". */
    public static String lastCheck(Optional<Instant> when, Instant now, ZoneId zone, Locale locale) {
        if (when.isEmpty()) {
            return ResourcesEngine.getString("updates.never-checked");
        }
        ZonedDateTime at = when.get().atZone(zone);
        String time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale));
        if (at.toLocalDate().equals(LocalDate.ofInstant(now, zone))) {
            return ResourcesEngine.getString("updates.checked-today", time);
        }
        return ResourcesEngine.getString("updates.checked-on",
                at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)), time);
    }

    /** The version of an offered release, "1.3.0-beta.2 (beta)" for a pre-release (the beta channel). */
    public static String version(Release release) {
        return release.prerelease() ? ResourcesEngine.getString("update.beta-version", release.version())
                : release.version();
    }

    /** The header: the running version and when it last checked. */
    public static String header(String version, String lastCheck) {
        return "Copybot " + version + SEPARATOR + lastCheck;
    }

    /**
     * What the line of a plugin says besides its versions: "up to date", that it travels with the offered Copybot
     * ("with Copybot X" while it can be ticked, "needs Copybot X" once Copybot is unticked), that it waits for a newer
     * Copybot, or nothing for a plugin this Copybot accepts.
     */
    public static String note(UpdatesModel model, UpdatesModel.Plugin plugin) {
        String offered = model.release().map(Release::version).orElse("");
        return switch (plugin.line()) {
            case UP_TO_DATE -> ResourcesEngine.getString("updates.plugin-up-to-date");
            case UPDATABLE -> "";
            case NEEDS_APP -> ResourcesEngine.getString(model.appChecked() ? "updates.with-app" : "updates.needs-app",
                    offered);
            case NEEDS_NEWER_APP -> ResourcesEngine.getString("updates.needs-newer-app");
        };
    }

    /**
     * The summary above the buttons, recomputed at each tick: "Copybot and 2 plugins \u00b7 48 MB \u00b7 one restart", "2
     * plugins \u00b7 one restart", "Copybot \u00b7 \u2026"; the size only when one is known; "Nothing to update" when nothing is
     * ticked.
     */
    public static String summary(UpdatesModel model) {
        if (model.nothingToApply()) {
            return ResourcesEngine.getString("updates.nothing");
        }
        int plugins = model.checkedModules().size();
        List<String> parts = new ArrayList<>();
        if (!model.appChecked()) {
            parts.add(ResourcesEngine.getString("updates.summary.plugins", plugins));
        } else if (plugins == 0) {
            parts.add(ResourcesEngine.getString("updates.summary.app"));
        } else {
            parts.add(ResourcesEngine.getString("updates.summary.app-and-plugins", plugins));
        }
        long size = model.knownSize();
        if (size > 0) {
            parts.add(ResourcesEngine.getString("updates.summary.size", size / MEGABYTE));
        }
        parts.add(ResourcesEngine.getString("updates.summary.one-restart"));
        return String.join(SEPARATOR, parts);
    }
}
