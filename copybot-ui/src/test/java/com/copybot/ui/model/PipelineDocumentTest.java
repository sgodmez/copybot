package com.copybot.ui.model;

import com.copybot.engine.PipelineReader;
import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.SecondaryOutputConfig.OnError;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument.BranchProblem;
import com.copybot.ui.model.PipelineDocument.Gap;
import com.copybot.ui.model.PipelineDocument.Problem;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.model.PipelineDocument.StepAddress;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.copybot.ui.model.TestCatalog.CATALOG;
import static com.copybot.ui.model.TestCatalog.EXIF_2;
import static com.copybot.ui.model.TestCatalog.LAUNCH;
import static com.copybot.ui.model.TestCatalog.MAIL;
import static com.copybot.ui.model.TestCatalog.READ;
import static com.copybot.ui.model.TestCatalog.THUMB;
import static com.copybot.ui.model.TestCatalog.WRITE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The pipeline being edited: a JSON tree of which only the known fields are rewritten (spec desktop-ui §3). */
public class PipelineDocumentTest {

    @TempDir
    Path tempDir;

    static final String PIPELINE = """
            {
              "comment": "kept as is",
              "inSteps": [
                { "action": "file.read", "filterCondition": "size > 0",
                  "actionConfig": { "path": "D:/DCIM", "future": { "x": 1 } } }
              ],
              "analyseSteps": [
                { "plugin": "com.missing", "action": "faces", "actionConfig": { "model": "big" } }
              ],
              "outStep": { "action": "file.write", "actionConfig": { "outPattern": "nas/{name}", "bufferSize": 8 } },
              "resume": { "mode": "state" },
              "ui": { "theme": "dark" }
            }
            """;

    private static JsonObject tree(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---- load / save ----

    @Test
    public void savingAnUntouchedDocumentKeepsEveryMember() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        PipelineDocument document = PipelineDocument.load(file);

        document.save(file);

        assertFalse(document.isModified());
        assertEquals(tree(PIPELINE), tree(Files.readString(file)));
    }

    @Test
    public void theSavedJsonIsPrettyAndKeepsSpecialCharacters() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"outStep\":{\"action\":\"file.write\",\"actionConfig\":{\"outPattern\":\"<nas>/{name}='x'\"}}}");

        String json = document.toJson();

        assertTrue(json.contains("<nas>/{name}='x'"), json);
        assertTrue(json.contains("\n  \"outStep\""), json);
        assertTrue(json.endsWith("}\n"), json);
    }

    @Test
    public void anUnreadablePipelineIsRefused() throws IOException {
        assertThrows(CopybotException.class, () -> PipelineDocument.load(tempDir.resolve("missing.json")));
        for (String json : List.of("not json {", "[1, 2]", "{\"inSteps\": [1]}", "{\"inSteps\": {}}", "{\"outStep\": []}")) {
            Path file = Files.writeString(tempDir.resolve("bad.json"), json);
            assertThrows(CopybotException.class, () -> PipelineDocument.load(file), json);
        }
    }

    @Test
    public void aNewDocumentHasNoStep() {
        PipelineDocument document = PipelineDocument.empty();

        for (Section section : Section.values()) {
            assertEquals(List.of(), document.steps(section));
        }
        assertEquals("{}\n", document.toJson());
        assertFalse(document.isModified());
    }

    @Test
    public void theStepsAreTheObjectsOfTheirSection() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        assertEquals("file.read", document.steps(Section.IN).getFirst().get("action").getAsString());
        assertEquals("faces", document.steps(Section.ANALYZE).getFirst().get("action").getAsString());
        assertEquals(List.of(), document.steps(Section.PROCESS));
        assertEquals("file.write", document.steps(Section.OUT).getFirst().get("action").getAsString());
    }

    // ---- pipeline fields ----

    /** Spec execution-mode §5: "plan" is the default, not written. */
    @Test
    public void theExecutionModeIsWrittenOnlyWhenNotPlan() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertEquals(ExecutionMode.PLAN, document.executionMode());

        document.setExecutionMode(ExecutionMode.PLAN);
        assertFalse(document.isModified(), "plan is the default");

        document.setExecutionMode(ExecutionMode.STREAMING);
        assertTrue(document.isModified());
        assertEquals(ExecutionMode.STREAMING, document.executionMode());
        assertEquals("streaming", tree(document.toJson()).get("execution").getAsString());
        document.setExecutionMode(ExecutionMode.AUTO);
        assertEquals("auto", tree(document.toJson()).get("execution").getAsString());

        document.setExecutionMode(ExecutionMode.PLAN);
        assertFalse(tree(document.toJson()).has("execution"));
        assertEquals(tree("{\"theme\":\"dark\"}"), tree(document.toJson()).getAsJsonObject("ui"), "the ui block stays as is");
        assertEquals(ExecutionMode.PLAN, PipelineDocument.parse("{\"execution\":\"later\"}").executionMode(),
                "an unknown mode reads as the default (the engine refuses it)");
    }

    // ---- run-end actions (spec run-end, editor) ----

    /** The former block is converted at the opening, like the engine reads it: saving writes the new form. */
    @Test
    public void aFormerPostProcessBlockOpensAsTheFirstRunEndAction() throws IOException {
        Path file = Files.writeString(tempDir.resolve("legacy.json"), """
                { "postProcess": { "command": "D:\\\\progs\\\\synoRight.exe", "args": ["gui", "{createdDirs}"] },
                  "runEnd": [ { "plugin": "notify-email", "action": "email.send" } ] }
                """);

        PipelineDocument document = PipelineDocument.load(file);

        List<JsonObject> runEnd = document.steps(Section.RUN_END);
        assertEquals(2, runEnd.size());
        assertEquals(tree("""
                { "action": "post.process", "when": ["success", "error"],
                  "actionConfig": { "command": "D:\\\\progs\\\\synoRight.exe", "args": ["gui", "{createdDirs}"] } }
                """), runEnd.getFirst());
        assertEquals("email.send", runEnd.get(1).get("action").getAsString(), "the existing entries follow");
        assertFalse(document.isModified(), "the same pipeline for the engine: closing asks nothing");

        document.save(file);
        JsonObject saved = tree(Files.readString(file));
        assertFalse(saved.has("postProcess"));
        assertEquals(2, saved.getAsJsonArray("runEnd").size(), "saved in the new form");
    }

    /** A pipeline of 0.8.0-beta.1 naming exec for an ffmpeg action opens on the ffmpeg plugin, as the engine reads it. */
    @Test
    public void anFfmpegStepOfTheExecPluginOpensAsAStepOfTheFfmpegPlugin() throws IOException {
        Path file = Files.writeString(tempDir.resolve("beta.json"), """
                { "actionSteps": [ { "plugin": "com.copybot.plugin.exec", "action": "ffmpeg.video", "version": "1.0.0",
                                     "actionConfig": { "quality": "small" } },
                                   { "plugin": "com.copybot.plugin.exec", "action": "exec.process" } ],
                  "secondaryOutputs": [ { "name": "Thumbs",
                      "actionSteps": [ { "plugin": "com.copybot.plugin.exec", "action": "ffmpeg.thumbnail" } ],
                      "outStep": { "action": "file.write" } } ] }
                """);

        PipelineDocument document = PipelineDocument.load(file);

        assertEquals(tree("""
                { "plugin": "com.copybot.plugin.ffmpeg", "action": "ffmpeg.video", "actionConfig": { "quality": "small" } }
                """), document.steps(Section.PROCESS).getFirst(), "the version of exec dropped");
        assertEquals("com.copybot.plugin.exec", document.steps(Section.PROCESS).get(1).get("plugin").getAsString());
        assertFalse(document.isModified(), "the same pipeline for the engine: closing asks nothing");

        document.save(file);
        PipelineConfig saved = PipelineReader.read(file);
        assertEquals("com.copybot.plugin.ffmpeg", saved.actionSteps().getFirst().plugin());
        assertNull(saved.actionSteps().getFirst().version());
        assertEquals("com.copybot.plugin.ffmpeg", saved.secondaryOutputs().getFirst().actionSteps().getFirst().plugin());
        JsonObject written = tree(Files.readString(file));
        assertEquals("com.copybot.plugin.ffmpeg", written.getAsJsonArray("secondaryOutputs").get(0).getAsJsonObject()
                .getAsJsonArray("actionSteps").get(0).getAsJsonObject().get("plugin").getAsString(),
                "saved in the new form");
    }

    @Test
    public void aBlockWithoutProgramIsDroppedAndARunEndThatIsNoListIsRefused() {
        PipelineDocument document = PipelineDocument.parse("{\"postProcess\": {\"command\": \" \"}}");
        assertEquals(List.of(), document.steps(Section.RUN_END));
        assertEquals("{}\n", document.toJson());

        assertThrows(IllegalArgumentException.class, () -> PipelineDocument.parse("{\"runEnd\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> PipelineDocument.parse("{\"runEnd\": [1]}"));
    }

    @Test
    public void runEndActionsAreAddedMovedAndRemovedLikeProcessSteps() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertTrue(document.canAdd(Section.RUN_END));

        document.addStep(Section.RUN_END, LAUNCH);
        document.addStep(Section.RUN_END, MAIL);
        assertTrue(document.isModified());
        assertEquals(JsonParser.parseString("""
                [ { "action": "post.process", "actionConfig": {} },
                  { "plugin": "notify-email", "action": "email.send", "actionConfig": {} } ]
                """), tree(document.toJson()).getAsJsonArray("runEnd"));
        assertEquals(List.of("success", "error"), document.runEndWhen(document.steps(Section.RUN_END).getFirst()),
                "no conditions: those of the former post-processing");

        assertTrue(document.canMove(Section.RUN_END, 1, -1));
        document.moveStep(Section.RUN_END, 1, -1);
        assertEquals("email.send", document.steps(Section.RUN_END).getFirst().get("action").getAsString());

        document.removeStep(Section.RUN_END, 0);
        assertEquals(List.of("post.process"), document.steps(Section.RUN_END).stream()
                .map(step -> step.get("action").getAsString()).toList());
    }

    @Test
    public void theConditionsOfARunEndActionKeepAtLeastOne() {
        PipelineDocument document = PipelineDocument.parse("""
                { "runEnd": [ { "action": "post.process", "when": ["error", "later"] } ] }
                """);
        JsonObject step = document.steps(Section.RUN_END).getFirst();
        assertEquals(List.of("error"), document.runEndWhen(step), "an unknown value is left out");

        document.setRunEndWhen(step, Set.of("cancelled", "success"));
        assertTrue(document.isModified());
        assertEquals(JsonParser.parseString("[\"success\", \"cancelled\"]"), step.get("when"), "in the order of the outcomes");
        assertEquals(List.of("success", "cancelled"), document.runEndWhen(step));

        PipelineDocument same = PipelineDocument.parse(document.toJson());
        same.setRunEndWhen(same.steps(Section.RUN_END).getFirst(), Set.of("success", "cancelled"));
        assertFalse(same.isModified(), "unchanged");

        assertThrows(IllegalArgumentException.class, () -> document.setRunEndWhen(step, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> document.setRunEndWhen(step, Set.of("success", "later")));
        assertEquals(JsonParser.parseString("[\"success\", \"cancelled\"]"), step.get("when"), "a refused change writes nothing");
    }

    @Test
    public void theTimeoutIsAnAdvancedFieldOfTheRunEndActionsOnly() {
        assertEquals(List.of("timeoutSeconds", "version"),
                PipelineDocument.advancedFields(Section.RUN_END).stream().map(ConfigField::name).toList());
        for (Section section : List.of(Section.IN, Section.ANALYZE, Section.PROCESS, Section.OUT)) {
            assertEquals(PipelineDocument.ADVANCED_FIELDS, PipelineDocument.advancedFields(section));
        }
        PipelineDocument document = PipelineDocument.parse("{ \"runEnd\": [ { \"action\": \"post.process\" } ] }");
        JsonObject step = document.steps(Section.RUN_END).getFirst();
        ConfigField timeout = PipelineDocument.advancedFields(Section.RUN_END).getFirst();

        document.setAdvancedText(step, timeout, "60");
        assertEquals(60, step.get("timeoutSeconds").getAsInt());
        document.setAdvancedText(step, timeout, "0");
        assertEquals(0, step.get("timeoutSeconds").getAsInt(), "0: no limit");
        assertThrows(IllegalArgumentException.class, () -> document.setAdvancedText(step, timeout, "-5"));
        assertThrows(IllegalArgumentException.class, () -> document.setAdvancedText(step, timeout, " -1.0 "));
        assertThrows(IllegalArgumentException.class, () -> document.setAdvancedText(step, timeout, "soon"));
        assertEquals("0", document.advancedText(step, timeout));
        document.setAdvancedText(step, timeout, "-0");
        assertEquals(0, step.get("timeoutSeconds").getAsInt(), "-0 is 0, not negative");
        document.setAdvancedText(step, timeout, "");
        assertFalse(step.has("timeoutSeconds"), "empty: the action's own limit");
    }

    /** Not blocking (the pipeline may be meant for another machine): the save only warns. */
    @Test
    public void theSecretsNotDefinedOnThisMachineAreReported() {
        PipelineDocument document = PipelineDocument.parse("""
                { "inSteps": [ { "action": "file.read", "actionConfig": { "path": "${env:DCIM}" } } ],
                  "runEnd": [ { "plugin": "notify-email", "action": "email.send",
                                "actionConfig": { "host": "smtp", "password": "${secret:hunter2}",
                                                  "auth": { "token": "Bearer ${secret:api}" },
                                                  "to": ["${secret:smtp}", "$${secret:literal}"] } } ] }
                """);

        assertEquals(List.of(new PipelineDocument.UnknownSecret(Section.RUN_END, 0, "hunter2"),
                        new PipelineDocument.UnknownSecret(Section.RUN_END, 0, "api")),
                document.unknownSecrets(List.of("smtp")));
        assertEquals(List.of(), document.unknownSecrets(List.of("smtp", "hunter2", "api")));
        assertEquals(List.of(), document.validate(CATALOG), "not a blocking problem");
    }

    @Test
    public void aRunEndActionNeedsItsRequiredFieldsAndAKnownCondition() {
        PipelineDocument document = PipelineDocument.parse("""
                { "runEnd": [ { "action": "post.process", "actionConfig": {} },
                              { "plugin": "notify-email", "action": "email.send", "when": [],
                                "actionConfig": { "host": "smtp" } } ] }
                """);

        assertEquals(List.of(new Problem(Section.RUN_END, 0, "command"), new Problem(Section.RUN_END, 1, "when")),
                document.validate(CATALOG));
    }

    private static JsonObject rule(String json) {
        return tree(json);
    }

    @Test
    public void groupingRulesAreAddedRemovedAndMovedAndTheEmptyBlockGoes() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertEquals(List.of(), document.groupingRules());

        document.addGroupingRule(rule("{\"preset\":\"iphone\"}"));
        document.addGroupingRule(rule("{\"type\":\"doubleExtension\",\"companions\":{\"xmp\":\"copy\"}}"));
        document.addGroupingRule(rule("{\"preset\":\"action-cam\"}"));
        assertTrue(document.isModified());
        assertEquals(3, document.groupingRules().size());

        document.moveGroupingRule(2, 0);
        assertEquals(List.of("action-cam", "iphone"), document.groupingRules().stream()
                .limit(2).map(r -> r.get("preset").getAsString()).toList());
        document.moveGroupingRule(0, 9);
        assertEquals("action-cam", document.groupingRules().getFirst().get("preset").getAsString(), "out of range");

        document.removeGroupingRule(0);
        document.removeGroupingRule(1);
        assertEquals(1, tree(document.toJson()).getAsJsonObject("grouping").getAsJsonArray("rules").size());
        document.removeGroupingRule(0);
        assertFalse(tree(document.toJson()).has("grouping"), "no rule removes the block");
    }

    @Test
    public void archivesRoundTripAndTheBlockGoesWhenNotUnfolded() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertFalse(document.archives().isUnfold());

        document.setArchives(new com.copybot.engine.pipeline.ArchivesConfig(true, true, false,
                List.of("**/*.jpg", " "), null));
        assertEquals(tree("{\"unfold\":true,\"nested\":true,\"mergeFolder\":false,\"include\":[\"**/*.jpg\"]}"),
                tree(document.toJson()).getAsJsonObject("archives"));
        com.copybot.engine.pipeline.ArchivesConfig read = PipelineDocument.parse(document.toJson()).archives();
        assertTrue(read.isUnfold() && read.isNested() && !read.isMergeFolder());
        assertEquals(List.of("**/*.jpg"), read.include());

        document.setArchives(new com.copybot.engine.pipeline.ArchivesConfig(false, true, true, null, null));
        assertFalse(tree(document.toJson()).has("archives"));
    }

    private static PipelineDocument withFileRead(String actionConfig, String... rules) {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\","
                + "\"actionConfig\":" + actionConfig + "}]}");
        for (String rule : rules) {
            document.addGroupingRule(rule(rule));
        }
        return document;
    }

    @Test
    public void companionExtensionsLeftOutByFileReadAreFoundAndAllowed() {
        PipelineDocument document = withFileRead("{\"path\":\"D:/p\",\"include\":[\"*.jpg\"]}",
                "{\"type\":\"sameBase\",\"main\":[\"jpg\"],\"companions\":{\"xmp\":\"copy\"}}");
        assertEquals(List.of("xmp"), document.companionExtensionsExcludedByFileRead(CATALOG));

        document.allowCompanionExtensions(CATALOG);
        assertEquals("[\"*.jpg\",\"**/*.xmp\"]", tree(document.toJson()).getAsJsonArray("inSteps").get(0)
                .getAsJsonObject().getAsJsonObject("actionConfig").get("include").toString());
        assertEquals(List.of(), document.companionExtensionsExcludedByFileRead(CATALOG));

        PipelineDocument excluded = withFileRead("{\"path\":\"D:/p\",\"exclude\":[\"**/*.XMP\",\"*.tmp\"]}",
                "{\"preset\":\"raw-jpeg-xmp\"}");
        assertEquals(List.of("xmp"), excluded.companionExtensionsExcludedByFileRead(CATALOG));
        excluded.allowCompanionExtensions(CATALOG);
        assertEquals(List.of(), excluded.companionExtensionsExcludedByFileRead(CATALOG));
        assertEquals("[\"*.tmp\"]", tree(excluded.toJson()).getAsJsonArray("inSteps").get(0).getAsJsonObject()
                .getAsJsonObject("actionConfig").get("exclude").toString());

        assertEquals(List.of(), withFileRead("{\"path\":\"D:/p\"}", "{\"preset\":\"iphone\"}")
                .companionExtensionsExcludedByFileRead(CATALOG), "no filter: nothing left out");
    }

    private static String actionConfigMember(PipelineDocument document, String name) {
        return String.valueOf(tree(document.toJson()).getAsJsonArray("inSteps").get(0).getAsJsonObject()
                .getAsJsonObject("actionConfig").get(name));
    }

    private static final String XMP_RULE = "{\"type\":\"sameBase\",\"main\":[\"jpg\"],\"companions\":{\"xmp\":\"copy\"}}";

    @Test
    public void theAllowedExtensionGoesIntoFoldersToo() {
        PipelineDocument document = withFileRead("{\"path\":\"D:/p\",\"include\":[\"**/*.jpg\"]}", XMP_RULE);
        assertEquals(List.of("xmp"), document.companionExtensionsExcludedByFileRead(CATALOG));
        document.allowCompanionExtensions(CATALOG);
        assertEquals("[\"**/*.jpg\",\"**/*.xmp\"]", actionConfigMember(document, "include"));
        assertEquals(List.of(), document.companionExtensionsExcludedByFileRead(CATALOG));

        PipelineDocument shallow = withFileRead("{\"path\":\"D:/p\",\"include\":[\"*.jpg\",\"*.xmp\"]}", XMP_RULE);
        assertEquals(List.of("xmp"), shallow.companionExtensionsExcludedByFileRead(CATALOG),
                "\"*.xmp\" does not match in sub-folders: the engine would drop them");

        PipelineDocument noInclude = withFileRead("{\"path\":\"D:/p\",\"exclude\":[\"*.xmp\"]}", XMP_RULE);
        noInclude.allowCompanionExtensions(CATALOG);
        assertEquals("null", actionConfigMember(noInclude, "include"), "an empty include stays empty");
        assertEquals("null", actionConfigMember(noInclude, "exclude"));
    }

    @Test
    public void onlyTheExcludesOfExactlyTheExtensionAreRemoved() {
        PipelineDocument document = withFileRead("{\"path\":\"D:/p\",\"exclude\":[\"*.XMP\",\"**/*.xmp\",\"*.tmp\","
                + "\"folder/**\",\"*.{xmp,thm}\"]}", XMP_RULE);
        document.allowCompanionExtensions(CATALOG);
        assertEquals("[\"*.tmp\",\"folder/**\",\"*.{xmp,thm}\"]", actionConfigMember(document, "exclude"));
        assertEquals(List.of("xmp"), document.companionExtensionsExcludedByFileRead(CATALOG), "the warning stays");
    }

    @Test
    public void anUnreadableRuleIsSkippedByTheWarnings() {
        PipelineDocument document = withFileRead("{\"path\":\"D:/p\",\"include\":[\"*.jpg\"]}", XMP_RULE,
                "{\"type\":\"sameBase\",\"companions\":[]}");
        assertEquals(2, document.groupingRules().size());
        assertEquals(List.of("xmp"), document.companionExtensionsExcludedByFileRead(CATALOG));
        assertFalse(document.folderRuleWithNonRecursiveFileRead(CATALOG));
        assertEquals(Optional.empty(), document.streamingGroupingWarning(CATALOG));
    }

    @Test
    public void theWarningsTakeTheRulesOfThePluginPresetsIntoAccount() {
        String takeout = "{\"preset\":\"takeout\",\"plugin\":\"com.acme.takeout\"}";
        PipelineDocument document = withFileRead("{\"path\":\"D:/p\",\"recursive\":false,\"include\":[\"*.jpg\"]}",
                takeout);
        assertEquals(List.of("json"), document.companionExtensionsExcludedByFileRead(TestCatalog.WITH_PRESETS));
        assertTrue(document.folderRuleWithNonRecursiveFileRead(TestCatalog.WITH_PRESETS));
        assertEquals(List.of(), document.companionExtensionsExcludedByFileRead(CATALOG), "plugin absent: no rule");
        assertFalse(document.folderRuleWithNonRecursiveFileRead(CATALOG));

        document.allowCompanionExtensions(TestCatalog.WITH_PRESETS);
        assertEquals("[\"*.jpg\",\"**/*.json\"]", actionConfigMember(document, "include"));
    }

    @Test
    public void theGroupingWarningsFollowTheInputsAndTheExecution() {
        PipelineDocument folder = withFileRead("{\"path\":\"D:/p\",\"recursive\":false}", "{\"preset\":\"web-page\"}");
        assertTrue(folder.folderRuleWithNonRecursiveFileRead(CATALOG));
        assertFalse(withFileRead("{\"path\":\"D:/p\"}", "{\"preset\":\"web-page\"}").folderRuleWithNonRecursiveFileRead(CATALOG));
        assertFalse(withFileRead("{\"path\":\"D:/p\",\"recursive\":false}", "{\"preset\":\"iphone\"}")
                .folderRuleWithNonRecursiveFileRead(CATALOG));

        PipelineDocument plugin = PipelineDocument.parse("{\"execution\":\"streaming\",\"inSteps\":["
                + "{\"action\":\"file.read\",\"actionConfig\":{\"path\":\"D:/p\"}},"
                + "{\"plugin\":\"com.x\",\"action\":\"ftp.read\",\"actionConfig\":{}}]}");
        assertEquals(Optional.empty(), plugin.streamingGroupingWarning(CATALOG), "no rule: nothing to say");
        plugin.addGroupingRule(rule("{\"preset\":\"iphone\"}"));
        assertEquals(Optional.of(new PipelineDocument.StreamingNote(
                        ResourcesEngine.getString("editor.grouping.warning.streaming-fallback", "ftp.read"), true)),
                plugin.streamingGroupingWarning(CATALOG), "an input that may not signal its folders");
        plugin.setExecutionMode(ExecutionMode.AUTO);
        assertEquals(Optional.empty(), plugin.streamingGroupingWarning(CATALOG), "not streaming");
        PipelineDocument builtIn = withFileRead("{\"path\":\"D:/p\"}", "{\"preset\":\"iphone\"}");
        builtIn.setExecutionMode(ExecutionMode.STREAMING);
        assertEquals(Optional.of(new PipelineDocument.StreamingNote(
                        ResourcesEngine.getString("editor.grouping.warning.streaming-by-folder"), false)),
                builtIn.streamingGroupingWarning(CATALOG), "file.read signals its folders");
    }

    @Test
    public void anInputWhoseActionSignalsItsFoldersIsProcessedFolderByFolder() {
        CatalogAction sftp = new CatalogAction("com.copybot.plugin.remote", "remote", "1.0.0", "sftp.read",
                com.copybot.engine.pipeline.StepType.IN, "SFTP", "", null, java.util.Map.of(), true);
        CatalogAction s3 = new CatalogAction("com.copybot.plugin.remote", "remote", "1.0.0", "s3.read",
                com.copybot.engine.pipeline.StepType.IN, "S3", "", null, java.util.Map.of(), false);
        StepCatalog catalog = TestCatalog.catalog(READ, WRITE, sftp, s3);
        PipelineDocument document = PipelineDocument.parse("{\"execution\":\"streaming\",\"inSteps\":["
                + "{\"plugin\":\"com.copybot.plugin.remote\",\"action\":\"sftp.read\",\"actionConfig\":{}}]}");
        document.addGroupingRule(rule("{\"preset\":\"iphone\"}"));
        assertEquals(Optional.of(new PipelineDocument.StreamingNote(
                        ResourcesEngine.getString("editor.grouping.warning.streaming-by-folder"), false)),
                document.streamingGroupingWarning(catalog), "sftp.read signals its folders");
        assertEquals(Optional.of(new PipelineDocument.StreamingNote(
                        ResourcesEngine.getString("editor.grouping.warning.streaming-fallback", "sftp.read"), true)),
                document.streamingGroupingWarning(CATALOG), "its plugin missing: it may not");

        PipelineDocument both = PipelineDocument.parse("{\"execution\":\"streaming\",\"inSteps\":["
                + "{\"plugin\":\"com.copybot.plugin.remote\",\"action\":\"sftp.read\",\"actionConfig\":{}},"
                + "{\"plugin\":\"com.copybot.plugin.remote\",\"action\":\"s3.read\",\"actionConfig\":{}}]}");
        both.addGroupingRule(rule("{\"preset\":\"iphone\"}"));
        assertEquals(Optional.of(new PipelineDocument.StreamingNote(
                        ResourcesEngine.getString("editor.grouping.warning.streaming-fallback", "s3.read"), true)),
                both.streamingGroupingWarning(catalog), "s3.read does not signal its folders");
    }

    @Test
    public void theConflictCheckIsWrittenOnlyWhenNotQuick() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertEquals(ConflictCheck.QUICK, document.conflictCheck());

        document.setConflictCheck(ConflictCheck.QUICK);
        assertFalse(document.isModified(), "quick is the default");

        document.setConflictCheck(ConflictCheck.FULL);
        assertTrue(document.isModified());
        assertEquals(ConflictCheck.FULL, document.conflictCheck());
        assertEquals("full", tree(document.toJson()).get("conflictCheck").getAsString());
        document.setConflictCheck(ConflictCheck.NONE);
        assertEquals("none", tree(document.toJson()).get("conflictCheck").getAsString());

        document.setConflictCheck(ConflictCheck.QUICK);
        assertFalse(tree(document.toJson()).has("conflictCheck"));
        assertEquals(ConflictCheck.QUICK, PipelineDocument.parse("{\"conflictCheck\":\"fast\"}").conflictCheck(),
                "an unknown value reads as the default (the engine refuses it)");
    }

    @Test
    public void theOutStepsMissingKeyPolicyDefaultsToError() {
        assertEquals("skip", PipelineDocument.parse(
                "{\"outStep\":{\"actionName\":\"file.write\",\"actionConfig\":{\"onMissingKey\":\"skip\"}}}")
                .outOnMissingKey());
        assertEquals("error", PipelineDocument.parse(
                "{\"outStep\":{\"actionName\":\"file.write\",\"actionConfig\":{}}}").outOnMissingKey());
        assertEquals("error", PipelineDocument.parse("{}").outOnMissingKey(), "no out step");
    }

    @Test
    public void theDestinationOptionsLiveInTheResumeBlockAndTheirDefaultsAreNotWritten() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertEquals("lastFound", document.destinationCheck());
        assertEquals("directory", document.destinationMatch());

        document.setDestinationCheck("everyFile");
        document.setDestinationMatch("file");
        assertTrue(document.isModified());
        assertEquals(tree("{\"mode\":\"state\",\"destinationCheck\":\"everyFile\",\"destinationMatch\":\"file\"}"),
                tree(document.toJson()).getAsJsonObject("resume"));
        assertEquals("everyFile", document.destinationCheck());
        assertEquals("file", document.destinationMatch());

        document.setDestinationCheck("lastFound");
        document.setDestinationMatch("directory");
        assertEquals(tree("{\"mode\":\"state\"}"), tree(document.toJson()).getAsJsonObject("resume"));
        assertThrows(IllegalArgumentException.class, () -> document.setDestinationCheck("sometimes"));
        assertThrows(IllegalArgumentException.class, () -> document.setDestinationMatch("name"));
        assertThrows(IllegalStateException.class, () -> PipelineDocument.empty().setDestinationCheck("everyFile"),
                "without resume block: it would turn the resume on");
    }

    @Test
    public void theResumeModeIsTheEffectiveOne() {
        assertEquals(Optional.empty(), PipelineDocument.empty().resumeMode());
        assertEquals(Optional.of("stateThenDestination"), PipelineDocument.parse("{\"resume\":{}}").resumeMode());
        assertEquals(Optional.of("state"), PipelineDocument.parse(PIPELINE).resumeMode());
    }

    @Test
    public void modeNoneIsNoResumeLikeNoBlock() {
        assertEquals(Optional.empty(), PipelineDocument.parse("{\"resume\":{\"mode\":\"none\"}}").resumeMode(),
                "the same behaviour: one choice in the editor");
        assertFalse(PipelineDocument.RESUME_MODES.contains("none"));

        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        document.setResumeMode("none");
        assertEquals(Optional.empty(), document.resumeMode());
        assertFalse(document.toJson().contains("\"resume\""), "written as no resume block");
        assertEquals(PlanViewModel.resumeModeText(null), PlanViewModel.resumeModeText("none"), "one label");
    }

    @Test
    public void noResumeRemovesTheBlockUnlessItHoldsOtherMembers() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        document.setResumeMode("destination");
        assertEquals("destination", tree(document.toJson()).getAsJsonObject("resume").get("mode").getAsString());

        document.setResumeMode(null);
        assertFalse(tree(document.toJson()).has("resume"));

        PipelineDocument other = PipelineDocument.parse("{\"resume\":{\"mode\":\"state\",\"keep\":1}}");
        other.setResumeMode(null);
        assertEquals(tree("{\"mode\":\"none\",\"keep\":1}"), tree(other.toJson()).getAsJsonObject("resume"));
        assertThrows(IllegalArgumentException.class, () -> other.setResumeMode("sometimes"));
    }

    // ---- atomic save ----

    @Test
    public void aFailingSaveLeavesTheOriginalFileIntactAndNoTempFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        PipelineDocument document = PipelineDocument.parse("{\"comment\":\"new\"}");
        document.setExecutionMode(ExecutionMode.STREAMING);

        assertThrows(IOException.class, () -> document.save(file, temp -> {
            assertTrue(Files.exists(temp), "the temp file exists when the hook runs");
            assertTrue(Files.size(temp) > 0, "the new content is in the temp file, not in the target");
            assertEquals(PIPELINE, Files.readString(file));
            throw new IOException("simulated failure before the move");
        }));

        assertEquals(PIPELINE, Files.readString(file));
        assertTrue(document.isModified(), "a failed save does not clear the modified flag");
        try (var entries = Files.list(tempDir)) {
            assertEquals(List.of("p.json"), entries.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    public void aMoveThatFailsLeavesNoTempFile() throws IOException {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Files.writeString(target.resolve("keep.txt"), "x");
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        assertThrows(IOException.class, () -> document.save(target));

        try (var entries = Files.list(tempDir)) {
            assertEquals(List.of("target"), entries.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    public void saveReplacesAnExistingFileAndClearsTheModifiedFlag() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), "old");
        PipelineDocument document = PipelineDocument.empty();
        document.setExecutionMode(ExecutionMode.STREAMING);

        document.save(file);

        assertFalse(document.isModified());
        assertEquals("streaming", tree(Files.readString(file)).get("execution").getAsString());
        try (var entries = Files.list(tempDir)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    public void aRuntimeFailureDuringSaveLeavesTheOriginalAndNoTempFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        assertThrows(IllegalStateException.class, () -> document.save(file, temp -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(PIPELINE, Files.readString(file));
        try (var entries = Files.list(tempDir)) {
            assertEquals(List.of("p.json"), entries.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    public void nullMembersSurviveALoadAndSave() throws IOException {
        String json = "{\"a\":null,\"inSteps\":[{\"action\":\"file.read\",\"actionConfig\":{\"b\":{\"c\":null}}}]}";
        Path file = Files.writeString(tempDir.resolve("p.json"), json);

        PipelineDocument.load(file).save(file);

        JsonObject saved = tree(Files.readString(file));
        assertEquals(tree(json), saved);
        assertTrue(saved.has("a"));
        assertTrue(saved.getAsJsonArray("inSteps").get(0).getAsJsonObject()
                .getAsJsonObject("actionConfig").getAsJsonObject("b").has("c"));
    }

    @Test
    public void noResumeOnAnAlreadyNoneBlockWithOtherMembersChangesNothing() {
        PipelineDocument document = PipelineDocument.parse("{\"resume\":{\"keep\":1,\"mode\":\"none\"}}");
        String before = document.toJson();

        document.setResumeMode(null);

        assertFalse(document.isModified());
        assertEquals(before, document.toJson());
    }

    // ---- steps ----

    @Test
    public void anAddedStepNamesItsPluginUnlessEmbedded() {
        PipelineDocument document = PipelineDocument.empty();

        document.addStep(Section.IN, READ);
        document.addStep(Section.ANALYZE, EXIF_2);

        assertEquals(tree("{\"action\":\"file.read\",\"actionConfig\":{}}"), document.steps(Section.IN).getFirst());
        assertEquals(tree("{\"plugin\":\"com.acme.exif\",\"action\":\"exif.read\",\"actionConfig\":{}}"),
                document.steps(Section.ANALYZE).getFirst());
        assertTrue(document.isModified());
    }

    @Test
    public void theOutputHoldsOneStep() {
        PipelineDocument document = PipelineDocument.empty();
        assertTrue(document.canAdd(Section.OUT));

        document.addStep(Section.OUT, WRITE);

        assertFalse(document.canAdd(Section.OUT));
        assertThrows(IllegalStateException.class, () -> document.addStep(Section.OUT, WRITE));
        assertTrue(tree(document.toJson()).get("outStep").isJsonObject());
        document.removeStep(Section.OUT, 0);
        assertTrue(document.canAdd(Section.OUT));
        assertFalse(tree(document.toJson()).has("outStep"));
    }

    @Test
    public void stepsMoveWithinTheirSection() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"inSteps\":[{\"action\":\"a\"},{\"action\":\"b\"},{\"action\":\"c\"}],\"after\":true}");

        document.moveStep(Section.IN, 2, -1);
        assertEquals(List.of("a", "c", "b"), actions(document));
        document.moveStep(Section.IN, 0, 1);
        assertEquals(List.of("c", "a", "b"), actions(document));

        assertFalse(document.canMove(Section.IN, 0, -1));
        assertFalse(document.canMove(Section.IN, 2, 1));
        document.moveStep(Section.IN, 0, -1);
        assertEquals(List.of("c", "a", "b"), actions(document), "no effect at the top");
        assertEquals(List.of("inSteps", "after"), List.copyOf(tree(document.toJson()).keySet()), "the list keeps its place");
    }

    @Test
    public void aRemovedStepLeavesItsListInTheFile() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        document.removeStep(Section.ANALYZE, 0);

        assertEquals(List.of(), document.steps(Section.ANALYZE));
        assertTrue(tree(document.toJson()).getAsJsonArray("analyseSteps").isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> document.removeStep(Section.ANALYZE, 0));
    }

    private static List<String> actions(PipelineDocument document) {
        return document.steps(Section.IN).stream().map(s -> s.get("action").getAsString()).toList();
    }

    // ---- exclusions ----

    private PipelineDocument twoCards() {
        return PipelineDocument.parse("""
                {
                  "inSteps": [
                    { "action": "file.read", "actionConfig": { "path": "%s", "exclude": [ "**/*.tmp" ] } },
                    { "plugin": "embedded", "action": "file.read", "actionConfig": { "path": "%s", "recursive": false } },
                    { "plugin": "com.other", "action": "file.read", "actionConfig": { "path": "%s" } }
                  ]
                }
                """.formatted(json(tempDir.resolve("a")), json(tempDir.resolve("b")), json(tempDir.resolve("c"))));
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static List<String> excludes(PipelineDocument document, int step) {
        JsonObject config = document.steps(Section.IN).get(step).getAsJsonObject("actionConfig");
        return config.has("exclude")
                ? config.getAsJsonArray("exclude").asList().stream().map(e -> e.getAsString()).toList() : List.of();
    }

    @Test
    public void excludingAFileAddsItsGlobToTheFileReadStepThatListsIt() {
        PipelineDocument document = twoCards();

        PipelineDocument.Exclusion exclusion = document.exclude(List.of(
                tempDir.resolve("a/DCIM/IMG[1].jpg"), tempDir.resolve("b/IMG_2.jpg")));

        assertEquals(List.of("DCIM/IMG\\[1\\].jpg", "IMG_2.jpg"), exclusion.added());
        assertEquals(List.of(), exclusion.uncovered());
        assertEquals(List.of("**/*.tmp", "DCIM/IMG\\[1\\].jpg"), excludes(document, 0), "added after the existing ones");
        assertEquals(List.of("IMG_2.jpg"), excludes(document, 1), "the list is created");
        assertTrue(document.isModified());
    }

    @Test
    public void aFileNoFileReadStepListsIsReportedUncovered() {
        PipelineDocument document = twoCards();

        PipelineDocument.Exclusion exclusion = document.exclude(List.of(
                tempDir.resolve("b/sub/IMG_3.jpg"), // not recursive: not listed
                tempDir.resolve("c/IMG_4.jpg"), // another plugin's action
                tempDir.resolve("elsewhere/IMG_5.jpg")));

        assertEquals(List.of(), exclusion.added());
        assertEquals(List.of(tempDir.resolve("b/sub/IMG_3.jpg"), tempDir.resolve("c/IMG_4.jpg"),
                tempDir.resolve("elsewhere/IMG_5.jpg")), exclusion.uncovered());
        assertEquals(List.of(), excludes(document, 2));
        assertFalse(document.isModified());
    }

    @Test
    public void aFileAlreadyExcludedIsNotAddedTwice() {
        PipelineDocument document = twoCards();
        document.exclude(List.of(tempDir.resolve("b/IMG_2.jpg")));

        PipelineDocument.Exclusion again = document.exclude(List.of(tempDir.resolve("b/img_2.JPG")));

        assertEquals(List.of(), again.added(), "the globs are case-insensitive");
        assertEquals(List.of(), again.uncovered());
        assertEquals(List.of("IMG_2.jpg"), excludes(document, 1));
    }

    // ---- fields ----

    private static ConfigField field(CatalogAction action, String path) {
        return action.configSchema().orElseThrow().field(path).orElseThrow();
    }

    @Test
    public void editingAKnownFieldKeepsTheUnknownOnes() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        PipelineDocument document = PipelineDocument.load(file);

        document.setConfigText(document.steps(Section.IN).getFirst(), field(READ, "path"), "E:/DCIM");
        document.save(file);

        JsonObject expected = tree(PIPELINE);
        expected.getAsJsonArray("inSteps").get(0).getAsJsonObject().getAsJsonObject("actionConfig")
                .addProperty("path", "E:/DCIM");
        assertEquals(expected, tree(Files.readString(file)));
    }

    // ---- sampling (spec pattern-helper §5) ----

    @Test
    public void editingTheOutStepChangesNeitherSamplingJson() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();

        document.setConfigText(document.steps(Section.OUT).getFirst(), field(WRITE, "outPattern"), "nas/{name}/x");

        assertEquals(in, document.samplingInJson(), "the input steps are unchanged");
        assertEquals(processing, document.samplingProcessingJson(), "the processing steps are unchanged");
    }

    @Test
    public void editingAnInputStepChangesOnlyTheSamplingInputJson() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();

        document.setConfigText(document.steps(Section.IN).getFirst(), field(READ, "path"), "E:/DCIM");

        assertNotEquals(in, document.samplingInJson(), "the input steps changed");
        assertEquals(processing, document.samplingProcessingJson(), "the processing steps are unchanged");
    }

    @Test
    public void addingAnAnalyseStepChangesOnlyTheSamplingProcessingJson() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();

        document.addStep(Section.ANALYZE, EXIF_2);

        assertEquals(in, document.samplingInJson(), "the input steps are unchanged");
        assertNotEquals(processing, document.samplingProcessingJson(), "the processing steps changed");
    }

    @Test
    public void theSamplingInputJsonIsEmptyWithoutInputSteps() {
        assertEquals("", PipelineDocument.empty().samplingInJson());
    }

    @Test
    public void theSamplingConfigIsTheDocumentReadAsAPipeline() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}]}");

        assertEquals(1, document.samplingConfig().inSteps().size());
        assertThrows(IllegalArgumentException.class,
                () -> PipelineDocument.parse("{\"inSteps\":[{\"maxConcurrency\":\"many\"}]}").samplingConfig(),
                "a document the engine cannot read");
    }

    @Test
    public void theSamplingConfigDoesNotFollowLaterEdits() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        PipelineConfig config = document.samplingConfig();

        document.setConfigText(document.steps(Section.IN).getFirst(), field(READ, "path"), "E:/DCIM");

        assertEquals("D:/DCIM", config.inSteps().getFirst().actionConfig().getAsJsonObject().get("path").getAsString(),
                "the sampler reads it on another thread: it shares nothing with the document");
    }

    @Test
    public void aFieldReadsAndWritesItsText() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();

        assertEquals("nas/{name}", document.configText(out, field(WRITE, "outPattern")));
        assertEquals("8", document.configText(out, field(WRITE, "bufferSize")));
        assertEquals("", document.configText(out, field(WRITE, "onConflict.compare")));

        document.setConfigText(out, field(WRITE, "bufferSize"), " 16 ");

        assertEquals(16, out.getAsJsonObject("actionConfig").get("bufferSize").getAsInt());
    }

    @Test
    public void theSameValueRewritesNothing() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();

        document.setConfigText(out, field(WRITE, "bufferSize"), "8");
        document.setConfigText(out, field(WRITE, "onConflict.compare"), "  ");

        assertFalse(document.isModified());
        assertEquals(tree(PIPELINE), tree(document.toJson()));
    }

    @Test
    public void aNestedFieldCreatesItsRecordAndAnEmptyOneIsRemoved() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();

        document.setConfigText(out, field(WRITE, "onConflict.compare"), "fullHash");
        assertEquals(tree("{\"compare\":\"fullHash\"}"), out.getAsJsonObject("actionConfig").getAsJsonObject("onConflict"));

        document.setConfigText(out, field(WRITE, "onConflict.compare"), "");
        assertFalse(out.getAsJsonObject("actionConfig").has("onConflict"));
        assertTrue(out.has("actionConfig"), "actionConfig itself stays");
    }

    @Test
    public void aStepWithoutActionConfigGetsOneOnItsFirstValue() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}]}");
        JsonObject in = document.steps(Section.IN).getFirst();

        assertEquals("", document.configText(in, field(READ, "path")));
        assertFalse(in.has("actionConfig"), "reading creates nothing");
        document.setConfigText(in, field(READ, "path"), "D:/");
        assertEquals("D:/", in.getAsJsonObject("actionConfig").get("path").getAsString());
    }

    @Test
    public void booleansAndListsHaveTheirOwnText() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();

        document.setConfigText(in, field(READ, "recursive"), "FALSE");
        document.setConfigText(in, field(READ, "include"), "**/*.NEF\n\n  **/*.jpg  \n");

        JsonObject config = in.getAsJsonObject("actionConfig");
        assertFalse(config.get("recursive").getAsBoolean());
        assertEquals(JsonParser.parseString("[\"**/*.NEF\",\"**/*.jpg\"]"), config.get("include"));
        assertEquals("**/*.NEF\n**/*.jpg", document.configText(in, field(READ, "include")));
    }

    @Test
    public void anInvalidValueIsRefusedAndChangesNothing() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();
        JsonObject out = document.steps(Section.OUT).getFirst();

        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(out, field(WRITE, "bufferSize"), "8 MB"));
        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(in, field(READ, "recursive"), "yes"));
        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(out, field(WRITE, "onConflict"), "x"));

        assertFalse(document.isModified());
    }

    @Test
    public void theAdvancedFieldsAreMembersOfTheStep() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();
        ConfigField maxConcurrency = PipelineDocument.ADVANCED_FIELDS.getFirst();
        ConfigField resources = PipelineDocument.ADVANCED_FIELDS.get(1);

        document.setAdvancedText(in, maxConcurrency, "4");
        document.setAdvancedText(in, resources, "gpu\nnet:flickr");

        assertEquals(4, in.get("maxConcurrency").getAsInt());
        assertEquals(JsonParser.parseString("[\"gpu\",\"net:flickr\"]"), in.get("resources"));
        assertEquals("size > 0", in.get("filterCondition").getAsString(), "a member outside the editor stays");
        assertEquals("4", document.advancedText(in, maxConcurrency));
        assertEquals(List.of("maxConcurrency", "resources", "priority", "version"),
                PipelineDocument.ADVANCED_FIELDS.stream().map(ConfigField::name).toList());
    }

    @Test
    public void aValueNotEditedAsTextIsShownAsJson() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();

        String json = PipelineDocument.json(in.getAsJsonObject("actionConfig").get("future"));

        assertEquals(tree("{\"x\":1}"), tree(json));
        assertEquals("", PipelineDocument.json(null));
        assertEquals(JsonParser.parseString("\"D:/DCIM\""), document.configValue(in, field(READ, "path")));
    }

    // ---- validation ----

    @Test
    public void emptyRequiredFieldsAreReported() {
        PipelineDocument document = PipelineDocument.empty();
        document.addStep(Section.IN, READ);
        document.addStep(Section.OUT, WRITE);

        assertEquals(List.of(new Problem(Section.IN, 0, "path"), new Problem(Section.OUT, 0, "outPattern")),
                document.validate(CATALOG));

        document.setConfigText(document.steps(Section.IN).getFirst(), field(READ, "path"), "D:/");
        document.setConfigText(document.steps(Section.OUT).getFirst(), field(WRITE, "outPattern"), "nas/{name}");
        assertEquals(List.of(), document.validate(CATALOG));
    }

    @Test
    public void aRequiredFieldOfARecordCountsOnlyWhenTheRecordIsThere() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();
        assertEquals(List.of(), document.validate(CATALOG), "no onConflict: nothing required");

        document.setConfigText(out, field(WRITE, "onConflict.ifIdentical"), "skip");

        assertEquals(List.of(new Problem(Section.OUT, 0, "onConflict.compare")), document.validate(CATALOG));
    }

    @Test
    public void stepsOfAMissingPluginAreNotValidatedButAStepNeedsAnAction() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"analyseSteps\":[{\"plugin\":\"com.missing\",\"action\":\"faces\"},{\"plugin\":\"com.acme.exif\"}]}");

        assertEquals(List.of(new Problem(Section.ANALYZE, 1, "action")), document.validate(CATALOG));
    }

    // ---- final fixes ----

    @Test
    public void anUnchangedTextIsNotParsedAgain() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"outStep\":{\"action\":\"file.write\",\"actionConfig\":{\"outPattern\":\"x\",\"bufferSize\":\"big\"}}}");
        JsonObject out = document.steps(Section.OUT).getFirst();

        assertDoesNotThrow(() -> document.setConfigText(out, field(WRITE, "bufferSize"), "big"),
                "the value shown, invalid in the file, is not refused while untouched");
        assertDoesNotThrow(() -> document.setConfigText(out, field(WRITE, "bufferSize"), " big "));
        assertFalse(document.isModified());
        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(out, field(WRITE, "bufferSize"), "bigger"));
    }

    @Test
    public void anIntegerAcceptsAZeroFractionWithinTheLongRange() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject out = document.steps(Section.OUT).getFirst();
        ConfigField bufferSize = field(WRITE, "bufferSize");

        document.setConfigText(out, bufferSize, "8.0");
        assertFalse(document.isModified(), "8.0 is the 8 already there");

        document.setConfigText(out, bufferSize, "16.00");
        assertEquals(JsonParser.parseString("16"), out.getAsJsonObject("actionConfig").get("bufferSize"));
        assertEquals("16", document.configText(out, bufferSize));
        // the schema does not tell an int from a long: the engine refuses an int out of range when loading
        document.setConfigText(out, bufferSize, "2147483648");
        assertEquals(JsonParser.parseString("2147483648"), out.getAsJsonObject("actionConfig").get("bufferSize"));
        document.setConfigText(out, bufferSize, String.valueOf(Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, out.getAsJsonObject("actionConfig").get("bufferSize").getAsLong());
        document.setConfigText(out, bufferSize, "1e10");
        assertEquals("10000000000", document.configText(out, bufferSize));
        document.setConfigText(out, bufferSize, String.valueOf(Long.MAX_VALUE));

        for (String invalid : List.of("8.5", "9223372036854775808", "-9223372036854775809", "1e19", "eight")) {
            assertThrows(IllegalArgumentException.class, () -> document.setConfigText(out, bufferSize, invalid), invalid);
        }
        assertEquals(Long.MAX_VALUE, out.getAsJsonObject("actionConfig").get("bufferSize").getAsLong());
    }

    @Test
    public void anAdvancedIntegerStaysWithinTheIntRange() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        JsonObject in = document.steps(Section.IN).getFirst();
        ConfigField maxConcurrency = PipelineDocument.ADVANCED_FIELDS.getFirst();
        ConfigField priority = PipelineDocument.ADVANCED_FIELDS.get(2);

        document.setAdvancedText(in, maxConcurrency, String.valueOf(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, in.get("maxConcurrency").getAsInt());
        // the engine reads them as Integer (PipelineStepConfig)
        assertThrows(IllegalArgumentException.class, () -> document.setAdvancedText(in, maxConcurrency, "2147483648"));
        assertThrows(IllegalArgumentException.class, () -> document.setAdvancedText(in, priority, "-2147483649"));
        assertEquals(Integer.MAX_VALUE, in.get("maxConcurrency").getAsInt());
    }

    @Test
    public void aNonStrictJsonFileIsDetected() throws IOException {
        assertFalse(PipelineDocument.parse(PIPELINE).isLenient());
        assertFalse(PipelineDocument.empty().isLenient());
        for (String json : List.of("{\n  // a comment\n  \"inSteps\": []\n}", "{ /* c */ }", "{'inSteps': []}",
                "{inSteps: []}", "{\"a\": \"x\";\"b\": 1}")) {
            PipelineDocument document = assertDoesNotThrow(() -> PipelineDocument.parse(json), json);
            assertTrue(document.isLenient(), json);
        }
        Path file = Files.writeString(tempDir.resolve("commented.json"), "{\n  # hash comment\n  \"inSteps\": []\n}");
        assertTrue(PipelineDocument.load(file).isLenient());
    }

    // ---- deferred review notes ----

    @Test
    public void duplicateKeysAreReportedWithTheirPath() {
        PipelineDocument document = PipelineDocument.parse("""
                { "a": 1, "inSteps": [ { "action": "x", "action": "y", "actionConfig": { "p": 1, "p": 2 } } ], "a": 2 }
                """);

        assertEquals(List.of("inSteps[0].action", "inSteps[0].actionConfig.p", "a"), document.duplicateKeys());
        assertEquals("y", document.steps(Section.IN).getFirst().get("action").getAsString(), "the last one wins");
        assertEquals(List.of(), PipelineDocument.parse(PIPELINE).duplicateKeys());
        assertEquals(List.of(), PipelineDocument.empty().duplicateKeys());
        assertEquals(List.of("b"), PipelineDocument.parse("{ // lenient\n 'b': 1, b: 2 }").duplicateKeys());
    }

    @Test
    public void aSaveKeepsThePosixPermissionsOfTheReplacedFile() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX file system");
        Path file = Files.writeString(tempDir.resolve("p.json"), PIPELINE);
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(file, permissions);

        PipelineDocument.load(file).save(file);

        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    @Test
    public void aMovedStepKeepsItsUnknownMembers() {
        PipelineDocument document = PipelineDocument.parse("""
                {"actionSteps":[{"action":"a","future":{"x":1}},{"action":"b","filterCondition":"size > 0"}]}
                """);

        document.moveStep(Section.PROCESS, 1, -1);

        assertEquals(tree("""
                {"actionSteps":[{"action":"b","filterCondition":"size > 0"},{"action":"a","future":{"x":1}}]}
                """), tree(document.toJson()));
    }

    @Test
    public void aRemovedStepLeavesItsNeighboursAsTheyWere() {
        PipelineDocument document = PipelineDocument.parse("""
                {"inSteps":[{"action":"a","k":1},{"action":"b"},{"action":"c","k":3}]}
                """);

        document.removeStep(Section.IN, 1);

        assertEquals(tree("{\"inSteps\":[{\"action\":\"a\",\"k\":1},{\"action\":\"c\",\"k\":3}]}"), tree(document.toJson()));
    }

    @Test
    public void aMoveThatCannotHappenLeavesTheDocumentUnmodified() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"a\"},{\"action\":\"b\"}],"
                + "\"outStep\":{\"action\":\"file.write\"}}");
        String before = document.toJson();

        document.moveStep(Section.IN, 0, 0);
        document.moveStep(Section.IN, 0, -1);
        document.moveStep(Section.IN, 1, 1);
        document.moveStep(Section.IN, 5, -1);
        document.moveStep(Section.OUT, 0, 1);

        assertFalse(document.isModified());
        assertEquals(before, document.toJson());
    }

    enum Speed { SLOW, FAST }

    record TuneConfig(Double quality, Speed speed, List<Integer> sizes) {
    }

    private static ConfigField tune(String path) {
        return ConfigSchema.of(TuneConfig.class).field(path).orElseThrow();
    }

    @Test
    public void decimalsAndEnumsAreWrittenFromTheirText() {
        PipelineDocument document = PipelineDocument.parse("{\"actionSteps\":[{\"action\":\"tune\"}]}");
        JsonObject step = document.steps(Section.PROCESS).getFirst();

        document.setConfigText(step, tune("quality"), " 0.85 ");
        document.setConfigText(step, tune("speed"), "FAST");

        assertEquals(JsonParser.parseString("{\"quality\":0.85,\"speed\":\"FAST\"}"), step.get("actionConfig"));
        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(step, tune("quality"), "0,85"));
        assertEquals("0.85", document.configText(step, tune("quality")));
    }

    @Test
    public void aListOfBlankLinesIsRemovedAndAnInvalidLineRefused() {
        PipelineDocument document = PipelineDocument.parse(
                "{\"actionSteps\":[{\"action\":\"tune\",\"actionConfig\":{\"sizes\":[1,2]}}]}");
        JsonObject step = document.steps(Section.PROCESS).getFirst();

        assertThrows(IllegalArgumentException.class, () -> document.setConfigText(step, tune("sizes"), "1\nbig\n3"));
        assertEquals(JsonParser.parseString("[1,2]"), step.getAsJsonObject("actionConfig").get("sizes"), "unchanged");
        assertFalse(document.isModified());

        document.setConfigText(step, tune("sizes"), " \n\n  \n");

        assertFalse(step.getAsJsonObject("actionConfig").has("sizes"));
        assertTrue(document.isModified());
    }

    @Test
    public void anAdvancedFieldMarksTheDocumentModifiedOnlyWhenItChanges() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\",\"priority\":2}]}");
        JsonObject in = document.steps(Section.IN).getFirst();
        ConfigField priority = PipelineDocument.ADVANCED_FIELDS.get(2);

        document.setAdvancedText(in, priority, " 2 ");
        assertFalse(document.isModified());

        document.setAdvancedText(in, priority, "");
        assertTrue(document.isModified());
        assertFalse(in.has("priority"));
    }

    @Test
    public void aResumeCursorIsNotAPipeline() {
        assertTrue(PipelineDocument.isResumeCursor(Path.of("dir", "sd-to-nas.state.json")));
        assertTrue(PipelineDocument.isResumeCursor(Path.of("SD.STATE.JSON")));
        assertFalse(PipelineDocument.isResumeCursor(Path.of("dir", "sd-to-nas.json")));
        assertFalse(PipelineDocument.isResumeCursor(Path.of("state.json")));
        assertFalse(PipelineDocument.isResumeCursor(Path.of("my.state.json.bak")));
    }

    @Test
    public void saveAsAppendsJsonOnlyToANameWithoutExtension() {
        assertEquals(Path.of("dir", "sd-to-nas.json"), PipelineDocument.withDefaultExtension(Path.of("dir", "sd-to-nas")));
        assertEquals(Path.of("dir", "sd-to-nas.json"), PipelineDocument.withDefaultExtension(Path.of("dir", "sd-to-nas.json")));
        assertEquals(Path.of("SD.JSON"), PipelineDocument.withDefaultExtension(Path.of("SD.JSON")));
        assertEquals(Path.of("dir", "foo.txt"), PipelineDocument.withDefaultExtension(Path.of("dir", "foo.txt")),
                "a typed extension is kept");
        assertEquals(Path.of("v1.2", "foo.json"), PipelineDocument.withDefaultExtension(Path.of("v1.2", "foo")),
                "only the file name counts");
        assertEquals(Path.of(".pipeline.json"), PipelineDocument.withDefaultExtension(Path.of(".pipeline")),
                "a leading dot is no extension");
        assertEquals(Path.of("foo..json"), PipelineDocument.withDefaultExtension(Path.of("foo.")),
                "nor a trailing one");
    }

    @Test
    public void aDocumentMissingPartsAreListed() {
        assertEquals(List.of(Gap.NO_INPUT, Gap.DOES_NOTHING), PipelineDocument.empty().gaps());
        assertEquals(List.of(Gap.NO_INPUT), PipelineDocument.parse("{\"inSteps\":[],\"outStep\":{\"action\":\"file.write\"}}").gaps());
        assertEquals(List.of(Gap.DOES_NOTHING), PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}]}").gaps());
    }

    @Test
    public void analyseStepsNeverCountAsDoingSomething() {
        PipelineDocument document = PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}],\"analyseSteps\":[{\"action\":\"exif\"}]}");

        assertEquals(List.of(Gap.DOES_NOTHING), document.gaps());
    }

    @Test
    public void anOutputOrAProcessStepIsEnoughAndANullOutputIsAbsent() {
        assertEquals(List.of(), PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}],\"outStep\":{\"action\":\"file.write\"}}").gaps());
        assertEquals(List.of(), PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}],\"actionSteps\":[{\"action\":\"rename\"}]}").gaps());
        assertEquals(List.of(Gap.DOES_NOTHING), PipelineDocument.parse("{\"inSteps\":[{\"action\":\"file.read\"}],\"outStep\":null}").gaps());
    }

    /** Shown in the popup of a pipeline that cannot be read: a translated text naming the section. */
    @Test
    public void aMalformedPipelineHasATranslatedMessage() {
        com.copybot.resources.ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        IllegalArgumentException notObject = assertThrows(IllegalArgumentException.class, () -> PipelineDocument.parse("[1]"));
        assertFalse(notObject.getMessage().startsWith("%"), notObject.getMessage());
        IllegalArgumentException notSteps = assertThrows(IllegalArgumentException.class,
                () -> PipelineDocument.parse("{\"inSteps\":[1]}"));
        assertFalse(notSteps.getMessage().startsWith("%"), notSteps.getMessage());
        assertTrue(notSteps.getMessage().contains("inSteps"), notSteps.getMessage());
    }

    @Test
    public void theGapsHaveATranslatedMessage() {
        for (Gap gap : Gap.values()) {
            assertFalse(gap.message().startsWith("%"), gap.name());
        }
    }

    // ---- secondary outputs (spec secondary-outputs §2, §8) ----

    static final String BRANCHES = """
            {
              "inSteps": [ { "action": "file.read", "actionConfig": { "path": "D:/DCIM" } } ],
              "outStep": { "action": "file.write", "actionConfig": { "outPattern": "nas/{name}" } },
              "secondaryOutputs": [
                { "name": "Miniatures", "onError": "warn", "future": { "x": 1 },
                  "actionSteps": [ { "plugin": "com.acme.thumbs", "action": "thumb.make",
                                     "actionConfig": { "size": 256 } } ],
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "thumbs/{name}" } } },
                { "name": "Original",
                  "outStep": { "action": "file.write", "actionConfig": { "outPattern": "orig/{name}" } } }
              ]
            }
            """;

    private static final StepCatalog BRANCH_CATALOG = TestCatalog.catalog(READ, WRITE, THUMB);

    private static StepAddress branchStep(int branch, Section section, int index) {
        return StepAddress.branch(branch, section, index);
    }

    private static List<String> kinds(PipelineDocument document) {
        return document.branchProblems().stream().map(p -> p.branch() + ":" + p.kind()).toList();
    }

    private static JsonObject branchJson(PipelineDocument document, int branch) {
        return tree(document.toJson()).getAsJsonArray("secondaryOutputs").get(branch).getAsJsonObject();
    }

    @Test
    public void theSecondaryOutputsAreReadAndSavedWithoutLoss() throws IOException {
        Path file = Files.writeString(tempDir.resolve("p.json"), BRANCHES);
        PipelineDocument document = PipelineDocument.load(file);

        assertEquals(2, document.branchCount());
        assertEquals("Miniatures", document.branchName(0));
        assertEquals(OnError.WARN, document.onError(0));
        assertEquals(OnError.FAIL, document.onError(1), "absent: fail");
        assertEquals(1, document.steps(0, Section.PROCESS).size());
        assertEquals(List.of(), document.steps(1, Section.PROCESS));
        assertEquals("orig/{name}", document.steps(1, Section.OUT).getFirst()
                .getAsJsonObject("actionConfig").get("outPattern").getAsString());
        assertSame(document.steps(0, Section.OUT).getFirst(), document.step(branchStep(0, Section.OUT, 0)));
        assertSame(document.steps(Section.OUT).getFirst(), document.step(StepAddress.main(Section.OUT, 0)));
        document.save(file);
        assertEquals(tree(BRANCHES), tree(Files.readString(file)), "the unknown members of a branch are kept");
        assertEquals(0, PipelineDocument.empty().branchCount());
    }

    @Test
    public void aMalformedSecondaryOutputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PipelineDocument.parse("{\"secondaryOutputs\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> PipelineDocument.parse("{\"secondaryOutputs\": [1]}"));
        IllegalArgumentException steps = assertThrows(IllegalArgumentException.class,
                () -> PipelineDocument.parse("{\"secondaryOutputs\": [{\"name\": \"a\", \"actionSteps\": [2]}]}"));
        assertTrue(steps.getMessage().contains("secondaryOutputs[0].actionSteps"), steps.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> PipelineDocument.parse("{\"secondaryOutputs\": [{\"name\": \"a\", \"outStep\": []}]}"));
        assertEquals(1, PipelineDocument.parse("{\"secondaryOutputs\": [{\"outStep\": null}]}").branchCount());
    }

    @Test
    public void anAddressInABranchIsAProcessStepOrItsOutStep() {
        assertThrows(IllegalArgumentException.class, () -> branchStep(0, Section.IN, 0));
        assertThrows(IllegalArgumentException.class, () -> branchStep(0, Section.RUN_END, 0));
        assertThrows(IllegalArgumentException.class, () -> branchStep(-2, Section.OUT, 0));
        assertTrue(branchStep(0, Section.OUT, 0).inBranch());
        assertFalse(StepAddress.main(Section.IN, 0).inBranch());
        assertEquals(new Problem(Section.OUT, 0, "outPattern"),
                new Problem(StepAddress.main(Section.OUT, 0), "outPattern"));
        assertEquals(Section.PROCESS, new Problem(branchStep(1, Section.PROCESS, 2), "size").section());
        assertEquals(2, new Problem(branchStep(1, Section.PROCESS, 2), "size").index());
    }

    @Test
    public void branchesAreAddedRenamedMovedAndRemoved() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);

        assertEquals(0, document.addBranch(" Miniatures "));
        assertEquals(1, document.addBranch("Original"));
        assertTrue(document.isModified());
        assertEquals(List.of("Miniatures", "Original"), List.of(document.branchName(0), document.branchName(1)));
        assertEquals(tree("{\"name\": \"Miniatures\"}"), branchJson(document, 0));

        document.setBranchName(1, "  Originals ");
        assertEquals("Originals", document.branchName(1));
        document.setBranchName(1, "");
        assertEquals("", document.branchName(1));
        assertFalse(branchJson(document, 1).has("name"));
        document.setBranchName(1, "Originals");

        assertFalse(document.canMoveBranch(0, -1));
        assertTrue(document.canMoveBranch(0, 1));
        assertFalse(document.canMoveBranch(1, 1));
        document.moveBranch(0, 1);
        assertEquals(List.of("Originals", "Miniatures"), List.of(document.branchName(0), document.branchName(1)));

        document.removeBranch(0);
        assertEquals(1, document.branchCount());
        assertEquals("Miniatures", document.branchName(0));
        document.removeBranch(0);
        assertEquals(tree(PIPELINE), tree(document.toJson()), "the member goes with the last branch");
        assertThrows(IndexOutOfBoundsException.class, () -> document.removeBranch(0));
    }

    @Test
    public void aSameNameRewritesNothing() {
        PipelineDocument document = PipelineDocument.parse(BRANCHES);

        document.setBranchName(0, " Miniatures ");

        assertFalse(document.isModified());
    }

    @Test
    public void theNumberOfBranchesIsCapped() {
        PipelineDocument document = PipelineDocument.empty();
        for (int i = 0; i < PipelineChecks.MAX_SECONDARY_OUTPUTS; i++) {
            assertTrue(document.canAddBranch());
            document.addBranch("b" + i);
        }

        assertFalse(document.canAddBranch());
        assertThrows(IllegalStateException.class, () -> document.addBranch("one more"));
    }

    @Test
    public void onErrorIsFailByDefaultAndWarnIsWritten() {
        PipelineDocument document = PipelineDocument.parse(BRANCHES);

        document.setOnError(0, OnError.WARN);
        document.setOnError(1, OnError.FAIL);
        assertFalse(document.isModified(), "the same values: nothing rewritten");

        document.setOnError(1, OnError.WARN);
        assertTrue(document.isModified());
        assertEquals("warn", branchJson(document, 1).get("onError").getAsString());
        document.setOnError(0, OnError.FAIL);
        assertFalse(branchJson(document, 0).has("onError"), "fail, the default, is not written");
        assertEquals(OnError.FAIL, document.onError(0));
    }

    @Test
    public void theStepsOfABranchAreAddedMovedAndRemoved() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        document.addBranch("Miniatures");

        JsonObject first = document.addStep(0, Section.PROCESS, THUMB);
        JsonObject second = document.addStep(0, Section.PROCESS, THUMB);
        assertTrue(document.canAdd(0, Section.OUT));
        JsonObject out = document.addStep(0, Section.OUT, WRITE);
        assertFalse(document.canAdd(0, Section.OUT));
        assertThrows(IllegalStateException.class, () -> document.addStep(0, Section.OUT, WRITE));
        assertThrows(IllegalArgumentException.class, () -> document.addStep(0, Section.ANALYZE, EXIF_2));
        assertEquals(List.of(first, second), document.steps(0, Section.PROCESS));
        assertEquals(List.of(out), document.steps(0, Section.OUT));
        assertEquals(tree("{\"plugin\": \"com.acme.thumbs\", \"action\": \"thumb.make\", \"actionConfig\": {}}"),
                first);
        assertEquals(1, document.steps(Section.OUT).size(), "the main output is another one");
        assertEquals(List.of(), document.steps(Section.PROCESS));

        first.getAsJsonObject("actionConfig").addProperty("size", 1);
        assertTrue(document.canMove(branchStep(0, Section.PROCESS, 0), 1));
        assertFalse(document.canMove(branchStep(0, Section.PROCESS, 0), -1));
        assertFalse(document.canMove(branchStep(0, Section.OUT, 0), 1));
        document.moveStep(branchStep(0, Section.PROCESS, 0), 1);
        assertEquals(1, document.steps(0, Section.PROCESS).get(1).getAsJsonObject("actionConfig")
                .get("size").getAsInt());

        document.removeStep(branchStep(0, Section.OUT, 0));
        assertEquals(List.of(), document.steps(0, Section.OUT));
        assertFalse(branchJson(document, 0).has("outStep"));
        document.removeStep(branchStep(0, Section.PROCESS, 0));
        document.removeStep(branchStep(0, Section.PROCESS, 0));
        assertEquals(List.of(), document.steps(0, Section.PROCESS));
        assertThrows(IndexOutOfBoundsException.class, () -> document.removeStep(branchStep(0, Section.PROCESS, 0)));
        assertThrows(IndexOutOfBoundsException.class, () -> document.steps(1, Section.PROCESS));
        assertEquals(tree("{\"name\": \"Miniatures\", \"actionSteps\": []}"), branchJson(document, 0),
                "an emptied list stays, like in the main pipeline");
    }

    @Test
    public void theStepsOfABranchHaveTheSameProblemsAsTheMainOnes() {
        PipelineDocument document = PipelineDocument.parse(BRANCHES);
        document.steps(0, Section.PROCESS).getFirst().getAsJsonObject("actionConfig").remove("size");
        document.steps(1, Section.OUT).getFirst().remove("action");

        assertEquals(List.of(new Problem(branchStep(0, Section.PROCESS, 0), "size"),
                        new Problem(branchStep(1, Section.OUT, 0), "action")),
                document.validate(BRANCH_CATALOG));
        assertEquals(PipelineDocument.ADVANCED_FIELDS, PipelineDocument.advancedFields(branchStep(0, Section.OUT, 0)));
    }

    @Test
    public void theUnknownSecretsOfABranchAreFound() {
        PipelineDocument document = PipelineDocument.parse(BRANCHES);
        document.steps(0, Section.PROCESS).getFirst().getAsJsonObject("actionConfig")
                .addProperty("token", "${secret:thumbs}");

        assertEquals(List.of(new PipelineDocument.UnknownSecret(branchStep(0, Section.PROCESS, 0), "thumbs")),
                document.unknownSecrets(List.of()));
        assertEquals(Section.PROCESS, document.unknownSecrets(List.of()).getFirst().section());
    }

    @Test
    public void aBranchNeedsAUniqueNameAKnownOnErrorAndAnOutput() {
        com.copybot.resources.ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        PipelineDocument document = PipelineDocument.parse("""
                { "secondaryOutputs": [
                    { "name": " ", "outStep": { "action": "file.write" } },
                    { "name": "Thumbs", "outStep": { "action": "file.write" } },
                    { "name": " thumbs ", "onError": "ignore", "outStep": { "action": "file.write" } },
                    { "name": "Original" },
                    { "outStep": { "action": "file.write" } } ] }
                """);

        assertEquals(List.of("0:NAME_MISSING", "2:NAME_DUPLICATE", "2:ON_ERROR_UNKNOWN", "3:OUT_MISSING",
                "4:NAME_MISSING"), kinds(document));
        assertTrue(document.branchProblems().stream().allMatch(p -> p.kind().blocking()));
        assertTrue(document.branchProblems().stream()
                .noneMatch(p -> p.message().isBlank() || p.message().startsWith("%")));
        assertEquals(List.of(), document.validate(TestCatalog.catalog()), "a branch problem is not a step problem");
        assertEquals(List.of(), PipelineDocument.parse(BRANCHES).branchProblems());
    }

    @Test
    public void moreThanEightBranchesIsAProblem() {
        StringBuilder json = new StringBuilder("{ \"secondaryOutputs\": [");
        for (int i = 0; i <= PipelineChecks.MAX_SECONDARY_OUTPUTS; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"name\": \"b").append(i).append("\", \"outStep\": {\"action\": \"file.write\"}}");
        }
        PipelineDocument document = PipelineDocument.parse(json.append("] }").toString());

        assertEquals(List.of(PipelineChecks.MAX_SECONDARY_OUTPUTS + ":TOO_MANY"), kinds(document));
    }

    @Test
    public void aSecondaryFileWriteThatDeletesTheSourceIsOnlyAWarning() {
        com.copybot.resources.ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        PipelineDocument document = PipelineDocument.parse("""
                { "outStep": { "action": "file.write", "actionConfig": { "deleteSource": true } },
                  "secondaryOutputs": [
                    { "name": "Thumbs",
                      "outStep": { "action": "file.write", "actionConfig": { "deleteSource": true } } },
                    { "name": "b", "outStep": { "action": "file.write", "actionConfig": { "deleteSource": false } } },
                    { "name": "c", "outStep": { "plugin": "com.acme", "action": "file.write",
                                               "actionConfig": { "deleteSource": true } } } ] }
                """);

        assertEquals(List.of("0:SOURCE_KEPT"), kinds(document), "the main output may delete the source");
        BranchProblem warning = document.branchProblems().getFirst();
        assertFalse(warning.kind().blocking());
        assertFalse(warning.message().startsWith("%"), warning.message());
        assertTrue(warning.message().contains("Thumbs"), warning.message());
    }

    @Test
    public void aSecondaryRemoteWriteThatDeletesTheSourceIsAWarningToo() {
        com.copybot.resources.ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        PipelineDocument document = PipelineDocument.parse("""
                { "outStep": { "action": "file.write" },
                  "secondaryOutputs": [
                    { "name": "a", "outStep": { "plugin": "com.copybot.plugin.remote", "action": "sftp.write",
                                               "actionConfig": { "deleteSource": true } } },
                    { "name": "b", "outStep": { "plugin": "com.copybot.plugin.remote", "action": "ftp.write",
                                               "actionConfig": { "deleteSource": true } } },
                    { "name": "c", "outStep": { "plugin": "com.copybot.plugin.remote", "action": "s3.write",
                                               "actionConfig": { "deleteSource": true } } },
                    { "name": "d", "outStep": { "plugin": "com.copybot.plugin.remote", "action": "s3.write",
                                               "actionConfig": { "deleteSource": false } } } ] }
                """);

        assertEquals(List.of("0:SOURCE_KEPT", "1:SOURCE_KEPT", "2:SOURCE_KEPT"), kinds(document));
    }
}
