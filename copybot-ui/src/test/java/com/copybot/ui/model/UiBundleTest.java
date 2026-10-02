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
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The texts of the desktop UI exist in both UI bundles (spec desktop-ui part 6). */
public class UiBundleTest {

    static final List<String> KEYS = List.of(
            "home.title", "home.open", "home.new", "home.remove", "home.missing", "home.empty", "home.file-filter",
            "recent.never-run", "recent.last-run",
            "pipeline.status.SUCCESS", "pipeline.status.ERROR", "pipeline.status.CANCELLED",
            "plan.target.more", "plan.back","plan.edit", "plan.steps", "plan.source", "plan.out-pattern", "plan.resume-mode",
            "plan.resume-mode.absent", "plan.resume-mode.none", "plan.resume-mode.state",
            "plan.resume-mode.destination", "plan.resume-mode.stateThenDestination", "plan.last-run",
            "plan.resume.all", "plan.resume.after", "plan.resume.from", "plan.resume.from-date",
            "plan.resume.source.NONE", "plan.resume.source.STATE", "plan.resume.source.DESTINATION",
            "plan.resume.source.MANUAL", "plan.resume.change", "plan.placeholder",
            "plan.filter.ALL", "plan.filter.TO_COPY", "plan.filter.SKIPPED", "plan.filter.ERRORS",
            "plan.column.date", "plan.column.target", "plan.menu.resume-from-here", "plan.prepare", "plan.copy",
            "plan.auto-execute", "plan.pause", "plan.resume", "plan.stop", "plan.progress", "plan.preparing",
            "plan.analysing", "plan.resolving",
            "plan.prepare-failed", "plan.finished",
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
            "editor.startProcessingWhileListing", "editor.resume-mode", "editor.autoExecute", "editor.advanced",
            "editor.advanced.maxConcurrency", "editor.advanced.maxConcurrency.description",
            "editor.advanced.resources", "editor.advanced.resources.description", "editor.advanced.priority",
            "editor.advanced.priority.description", "editor.advanced.version", "editor.advanced.version.description",
            "editor.plugin-not-found", "editor.plugin-failed", "editor.no-schema", "editor.default", "editor.browse",
            "editor.pattern-variables", "editor.add.title", "editor.add.empty", "editor.required-missing",
            "editor.invalid-value", "editor.json.title", "editor.discard", "editor.save-failed",
            "editor.invalid-fields",
            "item.status.SKIPPED.no-reason", "editor.save.tooltip", "editor.save-as.tooltip", "editor.show-json.tooltip",
            "editor.lenient", "editor.overwrite", "home.state-file");

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
        for (String file : List.of("uiBundle.properties", "uiBundle_fr.properties")) {
            Properties properties = bundle(file);
            for (String key : KEYS) {
                String value = properties.getProperty(key);
                assertNotNull(value, key + " in " + file);
                assertDoesNotThrow(() -> new MessageFormat(value), key + " in " + file);
                assertFalse(value.contains("\uFFFD"), key + " in " + file + " was re-encoded");
            }
        }
    }

    /** The engine formats every text through MessageFormat, which eats a lone apostrophe. */
    @Test
    public void noValueHasALoneApostrophe() throws IOException {
        for (String file : List.of("uiBundle.properties", "uiBundle_fr.properties")) {
            Properties properties = bundle(file);
            List<String> offending = properties.stringPropertyNames().stream()
                    .filter(key -> Pattern.compile("(?<!')'(?!')").matcher(properties.getProperty(key)).find())
                    .sorted().toList();
            assertTrue(offending.isEmpty(), "lone apostrophe (write '') in " + file + ": " + offending);
        }
    }

    @Test
    public void bothBundlesHaveTheSameKeys() throws IOException {
        assertEquals(bundle("uiBundle.properties").stringPropertyNames(),
                bundle("uiBundle_fr.properties").stringPropertyNames());
    }

    @Test
    public void everyKeyIsResolvedByTheEngine() {
        for (String key : KEYS) {
            assertFalse(ResourcesEngine.getString(key, "a", "b", "c", "d", "e").startsWith("%"), key);
        }
    }
}
