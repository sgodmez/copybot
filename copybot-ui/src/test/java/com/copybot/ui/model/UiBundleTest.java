package com.copybot.ui.model;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The texts of the desktop UI exist in every UI bundle (spec desktop-ui part 6). */
public class UiBundleTest {

    static final List<String> KEYS = List.of(
            "home.title", "home.open", "home.new", "home.remove", "home.missing", "home.empty", "home.file-filter",
            "recent.never-run", "recent.last-run",
            "pipeline.status.SUCCESS", "pipeline.status.ERROR", "pipeline.status.CANCELLED",
            "plan.target.more", "plan.back","plan.edit", "plan.resume-mode",
            "plan.details.show", "plan.details.hide", "plan.chip.no-setting", "plan.card.last-run",
            "plan.card.resume", "plan.badge.copied", "plan.badge.skipped", "plan.badge.error", "plan.badge.errors",
            "plan.resume-mode.none", "plan.resume-mode.state",
            "plan.resume-mode.destination", "plan.resume-mode.stateThenDestination",
            "plan.resume.all", "plan.resume.after", "plan.resume.from", "plan.resume.from-date",
            "plan.resume.source.NONE", "plan.resume.source.STATE", "plan.resume.source.DESTINATION",
            "plan.resume.source.MANUAL", "plan.resume.change", "plan.resume.count.imported", "plan.resume.count.before",
            "plan.placeholder",
            "plan.filter.ALL", "plan.filter.TO_COPY", "plan.filter.SKIPPED", "plan.filter.ERRORS", "plan.filter.CONFLICTS",
            "item.status.PENDING.exists.SAME_SIZE", "item.status.PENDING.exists.DIFFERENT_SIZE",
            "item.status.PENDING.exists.IDENTICAL", "item.status.PENDING.exists.DIFFERENT", "plan.conflicts.quick",
            "plan.conflicts.full", "editor.conflict-check", "editor.conflict-check.none", "editor.conflict-check.quick",
            "editor.conflict-check.full",
            "plan.column.date", "plan.column.target", "plan.menu.resume-from-here", "plan.prepare", "plan.copy",
            "plan.menu.ignore", "plan.menu.unignore", "plan.menu.exclude", "plan.exclude.title",
            "plan.exclude.confirm", "plan.exclude.add", "plan.exclude.already", "plan.exclude.uncovered",
            "plan.warning.nothing-to-copy",
            "plan.auto-execute", "plan.pause", "plan.resume", "plan.stop", "plan.progress", "plan.preparing",
            "plan.analysing", "plan.analysing-again", "plan.checking", "plan.resolving",
            "plan.prepare-failed", "plan.prepare-stopped", "plan.prepare-stopped.listing", "plan.resume.auto", "resume.dialog.auto", "resume.dialog.choose-file", "plan.finished", "item.status.NOT_ANALYSED", "plan.copy.left-out",
            "helper.loading", "helper.cancel", "helper.retry", "helper.sample", "helper.truncated", "helper.ok",
            "helper.missing", "helper.effect.error", "helper.effect.skip", "helper.effect.literal", "helper.expand",
            "helper.collapse", "helper.column.file", "helper.column.path", "helper.test-file", "helper.analysing",
            "helper.unpin", "helper.picker", "helper.filter", "helper.column.key", "helper.column.examples",
            "helper.missing-in", "helper.and-others",
            "item.status.PENDING", "item.status.WAITING_RESOURCES", "item.status.RUNNING",
            "item.status.RUNNING.percent", "item.status.DONE", "item.status.SKIPPED", "item.status.ERROR",
            "resume.dialog.title", "resume.dialog.all", "resume.dialog.date", "resume.dialog.file",
            "editor.title", "editor.untitled", "editor.section.PIPELINE", "editor.section.IN",
            "editor.section.ANALYZE", "editor.section.PROCESS", "editor.section.OUT", "editor.add", "editor.up",
            "editor.down", "editor.remove", "editor.save", "editor.save-as", "editor.show-json",
            "editor.execution", "editor.execution.plan", "editor.execution.auto", "editor.execution.streaming",
            "editor.execution.streaming-last-found", "editor.destination-check", "editor.destination-check.lastFound",
            "editor.destination-check.everyFile", "editor.destination-match", "editor.destination-match.directory",
            "editor.destination-match.file", "plan.prepare-and-copy", "plan.stream", "plan.resume.missing",
            "editor.resume-mode", "editor.advanced",
            "editor.advanced.maxConcurrency", "editor.advanced.maxConcurrency.description",
            "editor.advanced.resources", "editor.advanced.resources.description", "editor.advanced.priority",
            "editor.advanced.priority.description", "editor.advanced.version", "editor.advanced.version.description",
            "editor.plugin-not-found", "editor.plugin-failed", "editor.no-schema", "editor.default", "editor.browse",
            "editor.pattern-variables", "editor.add.title", "editor.add.empty", "editor.required-missing",
            "editor.invalid-value", "editor.json.title", "editor.discard", "editor.save-failed",
            "editor.invalid-fields",
            "item.status.SKIPPED.no-reason", "editor.save.tooltip", "editor.save-as.tooltip", "editor.show-json.tooltip",
            "editor.lenient", "editor.overwrite", "home.state-file", "editor.incomplete", "editor.save-anyway",
            "plan.menu.detail", "plan.menu.details", "plan.menu.analyse", "plan.detail.title", "plan.detail.position", "plan.detail.status", "plan.detail.filtered",
            "plan.detail.unsupported", "plan.detail.no-target", "editor.value.true", "editor.value.false",
            "error.title", "error.unexpected", "exit.title", "exit.running", "exit.stop", "exit.stopping",
            "about.title", "about.java", "about.javafx", "about.plugin-api", "about.license",
            "pipeline.document.not-object", "pipeline.document.not-steps",
            "update.title", "update.banner.app", "update.banner.plugins", "update.banner.app-and-plugins",
            "update.view", "menu.edit.updates", "update.dismiss", "update.busy",
            "update.windows-only", "update.unavailable-dev", "update.section", "update.auto",
            "update.checking", "update.up-to-date", "update.downloading", "update.cancel",
            "update.error.http", "update.error.network", "update.error.unreadable", "update.error.no-digest",
            "update.error.digest", "update.error.size", "update.error.stalled", "update.error.cancelled",
            "update.error.no-asset", "update.error.zip", "update.error.io", "update.error.launch",
            "update.other-instance", "update.admin", "update.no-release",
            "plugins.official.loading", "plugins.official.unreadable", "plugins.official.empty",
            "plugins.state.NOT_INSTALLED", "plugins.state.UP_TO_DATE", "plugins.state.UPDATE_AVAILABLE",
            "plugins.state.INSTALLED_INCOMPATIBLE", "plugins.state.INCOMPATIBLE", "plugins.state.update-fixes",
            "plugins.state.needs-newer-engine", "plugins.error.no-release", "editor.plugin-not-loaded",
            "plugins.action.INSTALL", "plugins.action.UPDATE", "plugins.action.REMOVE", "plugins.chosen.INSTALL",
            "plugins.chosen.UPDATE", "plugins.chosen.REMOVE", "plugins.action.undo",
            "plugins.apply", "plugins.pending", "plugins.license", "plugins.confirm.title", "plugins.confirm.intro",
            "plugins.confirm.install", "plugins.confirm.update", "plugins.confirm.remove", "plugins.confirm.licenses",
            "plugins.admin", "plugins.downloading", "plugins.unavailable-dev", "plugins.windows-only",
            "plugins.other-instance", "plugins.error.no-catalogue", "plugins.error.no-asset", "plugins.error.outside",
            "editor.plugin-not-installed",
            "editor.plugin-install", "plan.needs-plugins", "plan.install-plugins",
            "updates.title", "updates.check", "updates.never-checked", "updates.checked-today", "updates.checked-on",
            "updates.plugins", "updates.plugin-up-to-date", "updates.with-app", "updates.needs-app",
            "updates.needs-newer-app", "updates.whats-new", "updates.see-on-github", "updates.summary.app",
            "updates.summary.plugins", "updates.summary.app-and-plugins", "updates.summary.size",
            "updates.summary.one-restart", "updates.nothing", "updates.later", "updates.apply",
            "plugins.filter.ALL", "plugins.filter.INSTALLED", "plugins.filter.AVAILABLE", "plugins.card.none",
            "plugins.state.update-to", "plugins.card.failed", "plugins.card.unofficial", "plugins.card.depends-on",
            "plugins.card.missing", "plugins.card.pipeline-plugin", "plugins.card.license", "plugins.card.details",
            "plugins.embedded", "plugins.dev-path",
            "editor.section.RUN_END", "editor.run-end.when", "editor.run-end.when.success", "editor.run-end.when.error",
            "editor.run-end.when.cancelled", "editor.run-end.when.help", "editor.advanced.timeoutSeconds",
            "editor.advanced.timeoutSeconds.description", "editor.secret.new", "editor.secret.prompt",
            "menu.edit.secrets", "secrets.title", "secrets.help", "secrets.empty", "secrets.add", "secrets.change",
            "secrets.delete", "secrets.name", "secrets.value", "secrets.invalid-name", "secrets.empty-value",
            "secrets.replace", "secrets.delete.confirm", "secrets.unreadable", "plan.run-end", "editor.secret.unknown",
            "editor.secret.plain", "editor.secret.to-store", "editor.unknown-secrets");

    /** The keys formatted with a number. */
    private static final List<String> NUMBER_KEYS = List.of("plugins.pending",
            "updates.summary.plugins", "updates.summary.app-and-plugins", "updates.summary.size");

    static final List<String> BUNDLES = List.of("uiBundle.properties", "uiBundle_fr.properties", "uiBundle_it.properties");

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    /** The UI bundles are UTF-8 (ResourceBundle reads UTF-8 first); the new lines are ASCII escapes. */
    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = UiBundleTest.class.getResourceAsStream("/com/copybot/ui/i18n/" + name)) {
            assertNotNull(in, name);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    @Test
    public void everyKeyIsInBothBundlesAndIsAValidMessageFormat() throws IOException {
        for (String file : BUNDLES) {
            Properties properties = bundle(file);
            for (String key : KEYS) {
                String value = properties.getProperty(key);
                assertNotNull(value, key + " in " + file);
                assertDoesNotThrow(() -> new MessageFormat(value), key + " in " + file);
                assertFalse(value.contains("\uFFFD"), key + " in " + file + " was re-encoded");
            }
        }
    }

    /** The incompatibility texts name plugin API versions ({0} of the plugin, {1} of this Copybot), not Copybot ones. */
    @Test
    public void theIncompatibilityTextsShowBothApiVersions() throws IOException {
        for (String file : BUNDLES) {
            Properties properties = bundle(file);
            for (String key : List.of("plugins.state.INCOMPATIBLE", "plugins.state.needs-newer-engine")) {
                String text = new MessageFormat(properties.getProperty(key)).format(new Object[]{"2.0", "1.3"});
                assertTrue(text.contains("API") && text.contains("2.0"), key + " in " + file + ": " + text);
            }
            String incompatible = new MessageFormat(properties.getProperty("plugins.state.INCOMPATIBLE"))
                    .format(new Object[]{"2.0", "1.3"});
            assertTrue(incompatible.contains("1.3"), file + ": " + incompatible);
        }
    }

    /** The engine formats every text through MessageFormat, which eats a lone apostrophe. */
    @Test
    public void noValueHasALoneApostrophe() throws IOException {
        for (String file : BUNDLES) {
            Properties properties = bundle(file);
            List<String> offending = properties.stringPropertyNames().stream()
                    .filter(key -> Pattern.compile("(?<!')'(?!')").matcher(properties.getProperty(key)).find())
                    .sorted().toList();
            assertTrue(offending.isEmpty(), "lone apostrophe (write '') in " + file + ": " + offending);
        }
    }

    @Test
    public void everyBundleHasTheSameKeys() throws IOException {
        for (String file : BUNDLES) {
            assertEquals(bundle("uiBundle.properties").stringPropertyNames(), bundle(file).stringPropertyNames(), file);
        }
    }

    /** A \\u escape written without its backslash leaves its hex digits in the text ("Gi00f9"). */
    @Test
    public void theAccentedTextsAreDecoded() throws IOException {
        assertEquals("Down", bundle("uiBundle.properties").getProperty("editor.down"));
        assertEquals("Descendre", bundle("uiBundle_fr.properties").getProperty("editor.down"));
        assertEquals("Giù", bundle("uiBundle_it.properties").getProperty("editor.down"));
        for (String file : BUNDLES) {
            Properties properties = bundle(file);
            assertEquals("←", properties.getProperty("plan.back").substring(0, 1), file);
            assertTrue(properties.getProperty("plan.resume.change").endsWith("…"), file);
        }
    }

    @Test
    public void everyKeyIsResolvedByTheEngine() {
        for (String key : KEYS) {
            Object[] args = key.startsWith("plan.resume.count.") || NUMBER_KEYS.contains(key)
                    ? new Object[]{2}
                    : key.startsWith("update.banner.") ? new Object[]{"a", 2} : new Object[]{"a", "b", "c", "d", "e"};
            assertFalse(ResourcesEngine.getString(key, args).startsWith("%"), key);
        }
    }

    /** The counts are formatted with a plural choice: singular and plural. */
    @Test
    public void theResumeCountsHaveTheirPlural() {
        for (String key : List.of("plan.resume.count.imported", "plan.resume.count.before")) {
            String one = ResourcesEngine.getString(key, 1);
            String many = ResourcesEngine.getString(key, 803);
            assertTrue(one.startsWith("1 "), one);
            assertTrue(many.startsWith("803 "), many);
        }
    }

    /** The summary of the Updates window counts the plugins with a plural, in every language; the size is in MB. */
    @Test
    public void theUpdatesSummaryHasItsPlural() throws IOException {
        List<List<String>> expected = List.of(
                List.of("1 plugin", "2 plugins", "Copybot and 1 plugin", "Copybot and 2 plugins", "48.5 MB"),
                List.of("1 plugin", "2 plugins", "Copybot et 1 plugin", "Copybot et 2 plugins", "48.5 Mo"),
                List.of("1 plugin", "2 plugin", "Copybot e 1 plugin", "Copybot e 2 plugin", "48.5 MB"));
        for (int i = 0; i < BUNDLES.size(); i++) {
            Properties bundle = bundle(BUNDLES.get(i));
            MessageFormat plugins = new MessageFormat(bundle.getProperty("updates.summary.plugins"), Locale.ROOT);
            MessageFormat both = new MessageFormat(bundle.getProperty("updates.summary.app-and-plugins"), Locale.ROOT);
            MessageFormat size = new MessageFormat(bundle.getProperty("updates.summary.size"), Locale.ROOT);
            assertEquals(expected.get(i), List.of(plugins.format(new Object[]{1}), plugins.format(new Object[]{2}),
                    both.format(new Object[]{1}), both.format(new Object[]{2}), size.format(new Object[]{48.5})),
                    BUNDLES.get(i));
        }
    }

    /** The banner counts the plugins with a plural, in every language, with the release and/or the plugins. */
    @Test
    public void theUpdateBannerHasItsPlural() throws IOException {
        List<List<String>> expected = List.of(
                List.of("Updates available: Copybot 0.7.0", "Updates available: 1 plugin",
                        "Updates available: 2 plugins", "Updates available: Copybot 0.7.0 and 1 plugin",
                        "Updates available: Copybot 0.7.0 and 2 plugins"),
                List.of("Mises à jour disponibles : Copybot 0.7.0", "Mises à jour disponibles : 1 plugin",
                        "Mises à jour disponibles : 2 plugins",
                        "Mises à jour disponibles : Copybot 0.7.0 et 1 plugin",
                        "Mises à jour disponibles : Copybot 0.7.0 et 2 plugins"),
                List.of("Aggiornamenti disponibili: Copybot 0.7.0", "Aggiornamenti disponibili: 1 plugin",
                        "Aggiornamenti disponibili: 2 plugin", "Aggiornamenti disponibili: Copybot 0.7.0 e 1 plugin",
                        "Aggiornamenti disponibili: Copybot 0.7.0 e 2 plugin"));
        for (int i = 0; i < BUNDLES.size(); i++) {
            Properties bundle = bundle(BUNDLES.get(i));
            MessageFormat app = new MessageFormat(bundle.getProperty("update.banner.app"), Locale.ROOT);
            MessageFormat plugins = new MessageFormat(bundle.getProperty("update.banner.plugins"), Locale.ROOT);
            MessageFormat both = new MessageFormat(bundle.getProperty("update.banner.app-and-plugins"), Locale.ROOT);
            assertEquals(expected.get(i), List.of(app.format(new Object[]{"0.7.0", 0}),
                    plugins.format(new Object[]{"0.7.0", 1}), plugins.format(new Object[]{"0.7.0", 2}),
                    both.format(new Object[]{"0.7.0", 1}), both.format(new Object[]{"0.7.0", 2})), BUNDLES.get(i));
        }
    }

    /** The summary of the photos whose time zone is suggested: singular and plural, in every language. */
    @Test
    public void theSuggestedZoneCountHasItsPlural() throws IOException {
        List<List<String>> expected = List.of(
                List.of("1 photo of a badly set camera", "2 photos of a badly set camera"),
                // a typographic apostrophe: a quote in a choice is read twice by MessageFormat (the plural lost it)
                List.of("1 photo d’un appareil mal réglé", "2 photos d’un appareil mal réglé"),
                List.of("1 foto di una fotocamera impostata male", "2 foto di una fotocamera impostata male"));
        for (int i = 0; i < BUNDLES.size(); i++) {
            MessageFormat format = new MessageFormat(bundle(BUNDLES.get(i)).getProperty("plan.zone.suggested-count"), Locale.ROOT);
            assertEquals(expected.get(i), List.of(format.format(new Object[]{1}), format.format(new Object[]{2})), BUNDLES.get(i));
        }
    }
}
