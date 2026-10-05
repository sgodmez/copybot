package com.copybot.ui.model;

import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.ui.model.PipelineDocument.Gap;
import com.copybot.ui.model.PipelineDocument.Problem;
import com.copybot.ui.model.PipelineDocument.Section;
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
import static com.copybot.ui.model.TestCatalog.READ;
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

    @Test
    public void theDestinationOptionsLiveInTheResumeBlockAndTheirDefaultsAreNotWritten() {
        PipelineDocument document = PipelineDocument.parse(PIPELINE);
        assertEquals("dichotomy", document.destinationCheck());
        assertEquals("directory", document.destinationMatch());

        document.setDestinationCheck("everyFile");
        document.setDestinationMatch("file");
        assertTrue(document.isModified());
        assertEquals(tree("{\"mode\":\"state\",\"destinationCheck\":\"everyFile\",\"destinationMatch\":\"file\"}"),
                tree(document.toJson()).getAsJsonObject("resume"));
        assertEquals("everyFile", document.destinationCheck());
        assertEquals("file", document.destinationMatch());

        document.setDestinationCheck("dichotomy");
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

    @Test
    public void theGapsHaveATranslatedMessage() {
        for (Gap gap : Gap.values()) {
            assertFalse(gap.message().startsWith("%"), gap.name());
        }
    }
}
