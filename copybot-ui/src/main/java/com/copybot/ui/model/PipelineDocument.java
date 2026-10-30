package com.copybot.ui.model;

import com.copybot.engine.listing.GroupingPresets;
import com.copybot.engine.pipeline.ArchivesConfig;
import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.RunEndMigration;
import com.copybot.engine.pipeline.RunEndStepConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.embedded.actions.FileReadAction;
import com.copybot.plugin.api.config.FieldKind;
import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.JsonTexts;
import com.copybot.utils.GsonUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A pipeline file being edited (spec desktop-ui §3): its JSON tree, of which only the fields the editor
 * knows are rewritten; every other member (unknown fields, steps of a plugin not loaded, fields outside the
 * schema) is kept as is. Not thread-safe: used from the JavaFX thread.
 */
public final class PipelineDocument {

    /** The sections of a pipeline, in the order of the editor tree. */
    public enum Section {
        IN("inSteps", StepType.IN),
        ANALYZE("analyseSteps", StepType.ANALYZE),
        PROCESS("actionSteps", StepType.PROCESS),
        /** at most one step, the "outStep" object */
        OUT("outStep", StepType.OUT),
        /** the actions run once the copy is over (spec run-end), like the run, after the out step */
        RUN_END(RunEndMigration.RUN_END, StepType.RUN_END);

        private final String jsonName;
        private final StepType stepType;

        Section(String jsonName, StepType stepType) {
            this.jsonName = jsonName;
            this.stepType = stepType;
        }

        public String jsonName() {
            return jsonName;
        }

        public StepType stepType() {
            return stepType;
        }
    }

    /**
     * The resume modes, as written in the pipeline ("resume.mode"); "none" behaves like no resume block, so it is not
     * one more choice: no resume is {@code null}.
     */
    public static final List<String> RESUME_MODES = List.of("state", "destination", "stateThenDestination");

    /** The mode of a "resume" block without mode (see {@code ResumeConfig.effectiveMode}). */
    private static final String DEFAULT_RESUME_MODE = "stateThenDestination";

    /** The destination checks, as written ("resume.destinationCheck"), the default first (spec execution-mode §2). */
    public static final List<String> DESTINATION_CHECKS = List.of("lastFound", "everyFile");

    /** What counts as imported at the destination, as written ("resume.destinationMatch"), the default first. */
    public static final List<String> DESTINATION_MATCHES = List.of("directory", "file");

    /** A required field left empty: the step at index of the section, the field path in its actionConfig. */
    public record Problem(Section section, int index, String fieldPath) {
    }

    /** The "Advanced" fields of every step (spec desktop-ui §3), members of the step object itself. */
    public static final List<ConfigField> ADVANCED_FIELDS = List.of(
            advanced("maxConcurrency", FieldKind.INTEGER, null),
            advanced("resources", FieldKind.LIST, advanced("resources", FieldKind.STRING, null)),
            advanced("priority", FieldKind.INTEGER, null),
            advanced("version", FieldKind.STRING, null));

    /** The member of a run-end action that bounds its time, in seconds (absent: its default, 0: no limit). */
    private static final String TIMEOUT = "timeoutSeconds";

    /**
     * The "Advanced" fields of a run-end action: an entry of "runEnd" has no concurrency, resources nor priority (it
     * runs alone, after the copy), but a time limit.
     */
    private static final List<ConfigField> RUN_END_ADVANCED_FIELDS = List.of(
            advanced(TIMEOUT, FieldKind.INTEGER, null),
            advanced("version", FieldKind.STRING, null));

    /** The outcomes of a run a run-end action can run for ("when"), in the order the editor writes them. */
    public static final List<String> RUN_END_OUTCOMES = List.of(
            RunEndStepConfig.SUCCESS, RunEndStepConfig.ERROR, RunEndStepConfig.CANCELLED);

    private static final String WHEN = "when";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    private static final String RESUME_CURSOR_SUFFIX = ".state.json";

    private final JsonObject root;
    private final boolean lenient;
    private final List<String> duplicateKeys;
    private boolean modified;

    private PipelineDocument(JsonObject root, boolean lenient, List<String> duplicateKeys) {
        this.root = root;
        this.lenient = lenient;
        this.duplicateKeys = List.copyOf(duplicateKeys);
    }

    /** A new pipeline: no step. */
    public static PipelineDocument empty() {
        return new PipelineDocument(new JsonObject(), false, List.of());
    }

    /**
     * A resume cursor written by the engine next to its pipeline ("sd-to-nas.state.json"), not a pipeline:
     * neither opened nor overwritten by the UI.
     */
    public static boolean isResumeCursor(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(RESUME_CURSOR_SUFFIX);
    }

    /**
     * The file a "Save as" writes to: ".json" appended when the name typed has no extension at all
     * ("sd-to-nas", ".pipeline", "foo."); any typed extension is kept ("foo.txt").
     */
    public static Path withDefaultExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? path : path.resolveSibling(name + ".json");
    }

    /**
     * The file was read as non-strict JSON (comments, single quotes, unquoted names...), which the engine
     * accepts: saving rewrites it as strict JSON, its comments and layout are lost.
     */
    public boolean isLenient() {
        return lenient;
    }

    /**
     * The members written more than once in an object of the file ("inSteps[0].action"), in file order:
     * like the engine, the last value is the one read, and saving drops the others.
     */
    public List<String> duplicateKeys() {
        return duplicateKeys;
    }

    /** @throws CopybotException pipeline.not-found, pipeline.not-json (also for a step that is not an object) */
    public static PipelineDocument load(Path path) {
        if (!Files.isReadable(path)) {
            throw CopybotException.ofResource("pipeline.not-found", path);
        }
        try {
            return parse(Files.readString(path));
        } catch (IOException | IllegalArgumentException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", path);
        }
    }

    /** @throws IllegalArgumentException not a JSON object, or a section that is not made of objects */
    public static PipelineDocument parse(String json) {
        JsonElement tree;
        try {
            tree = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!tree.isJsonObject()) {
            throw new IllegalArgumentException(ResourcesEngine.getString("pipeline.document.not-object"));
        }
        JsonObject root = tree.getAsJsonObject();
        // the former "postProcess" block becomes the first run-end action, as the engine reads it: the same pipeline,
        // so the document is not marked modified (closing asks nothing), but the next save writes the new form
        RunEndMigration.migrate(root);
        for (Section section : Section.values()) {
            JsonElement value = root.get(section.jsonName());
            boolean valid = value == null || value.isJsonNull()
                    || (section == Section.OUT ? value.isJsonObject() : isArrayOfObjects(value));
            if (!valid) {
                throw new IllegalArgumentException(
                        ResourcesEngine.getString("pipeline.document.not-steps", section.jsonName()));
            }
        }
        return new PipelineDocument(root, !JsonTexts.isStrictJson(json), JsonTexts.duplicateKeys(json));
    }

    private static boolean isArrayOfObjects(JsonElement value) {
        if (!value.isJsonArray()) {
            return false;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                return false;
            }
        }
        return true;
    }

    /** Pretty-printed JSON, what {@link #save} writes. */
    public String toJson() {
        return GSON.toJson(root) + "\n";
    }

    /**
     * Writes the pipeline without ever overwriting the file in place: the content goes to a temp file of the
     * same directory, which then replaces the target (atomically when the file system can). On any failure the
     * temp file is deleted and the target is left as it was. On a POSIX file system the replaced file keeps
     * its permissions (a temp file is created owner-only).
     */
    public void save(Path path) throws IOException {
        save(path, temp -> { });
    }

    /** Test seam: {@code beforeMove} runs once the temp file is written, before it replaces the target. */
    void save(Path path, TempFileHook beforeMove) throws IOException {
        Path target = path.toAbsolutePath();
        Path temp = Files.createTempFile(target.getParent(), "pipeline-", ".tmp");
        try {
            keepPosixPermissions(target, temp);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer content = ByteBuffer.wrap(toJson().getBytes(StandardCharsets.UTF_8));
                while (content.hasRemaining()) {
                    channel.write(content);
                }
                channel.force(true);
            }
            beforeMove.accept(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        modified = false;
    }

    /** Gives the temp file the permissions of the file it replaces, when there is one on a POSIX file system. */
    private static void keepPosixPermissions(Path target, Path temp) throws IOException {
        if (!Files.exists(target) || !Files.getFileStore(temp).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return;
        }
        Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
    }

    @FunctionalInterface
    interface TempFileHook {
        void accept(Path temp) throws IOException;
    }

    /** Something was changed since the load or the last save. */
    public boolean isModified() {
        return modified;
    }

    // ---- pipeline ----

    /**
     * The effective resume mode: empty without "resume" block or with mode "none" (the same behaviour),
     * stateThenDestination for a block without mode.
     */
    public Optional<String> resumeMode() {
        JsonElement resume = root.get("resume");
        if (resume == null || !resume.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement mode = resume.getAsJsonObject().get("mode");
        String effective = mode != null && mode.isJsonPrimitive() ? mode.getAsString() : DEFAULT_RESUME_MODE;
        return "none".equals(effective) ? Optional.empty() : Optional.of(effective);
    }

    /**
     * @param mode one of {@link #RESUME_MODES}, null for no resume: the block is removed, unless it holds
     *             other members (then its mode becomes "none")
     */
    public void setResumeMode(String mode) {
        if ("none".equals(mode)) {
            mode = null; // the same behaviour as no resume block
        }
        if (Objects.equals(mode, resumeMode().orElse(null))) {
            return;
        }
        JsonElement resume = root.get("resume");
        if (mode == null) {
            JsonObject block = resume.getAsJsonObject();
            JsonElement current = block.get("mode");
            if (block.size() > 1 && current != null && current.isJsonPrimitive() && "none".equals(current.getAsString())) {
                return; // already "no resume" with other members: nothing to change
            }
            block.remove("mode");
            if (block.isEmpty()) {
                root.remove("resume");
            } else {
                block.addProperty("mode", "none");
            }
        } else {
            if (!RESUME_MODES.contains(mode)) {
                throw new IllegalArgumentException(mode);
            }
            if (resume == null || !resume.isJsonObject()) {
                resume = new JsonObject();
                root.add("resume", resume);
            }
            resume.getAsJsonObject().addProperty("mode", mode);
        }
        modified = true;
    }

    /** "resume.destinationCheck" (spec execution-mode §2), the search from the end when absent or unknown. */
    public String destinationCheck() {
        return resumeOption("destinationCheck", DESTINATION_CHECKS);
    }

    /**
     * @param check one of {@link #DESTINATION_CHECKS}; the default (lastFound) removes the member
     * @throws IllegalStateException without resume block: writing one would turn the resume on
     */
    public void setDestinationCheck(String check) {
        setResumeOption("destinationCheck", DESTINATION_CHECKS, check);
    }

    /** "resume.destinationMatch" (spec execution-mode §2), the target directory when absent or unknown. */
    public String destinationMatch() {
        return resumeOption("destinationMatch", DESTINATION_MATCHES);
    }

    /**
     * @param match one of {@link #DESTINATION_MATCHES}; the default (directory) removes the member
     * @throws IllegalStateException without resume block
     */
    public void setDestinationMatch(String match) {
        setResumeOption("destinationMatch", DESTINATION_MATCHES, match);
    }

    private String resumeOption(String member, List<String> values) {
        JsonElement resume = root.get("resume");
        JsonElement value = resume != null && resume.isJsonObject() ? resume.getAsJsonObject().get(member) : null;
        String name = value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        return name != null && values.contains(name) ? name : values.getFirst();
    }

    private void setResumeOption(String member, List<String> values, String value) {
        if (value == null || !values.contains(value)) {
            throw new IllegalArgumentException(value);
        }
        JsonElement resume = root.get("resume");
        if (resume == null || !resume.isJsonObject()) {
            throw new IllegalStateException("no resume block");
        }
        JsonObject block = resume.getAsJsonObject();
        if (value.equals(values.getFirst())) {
            if (block.remove(member) != null) {
                modified = true;
            }
            return;
        }
        if (value.equals(resumeOption(member, values)) && block.has(member)) {
            return;
        }
        block.addProperty(member, value);
        modified = true;
    }

    /** "execution" (spec execution-mode §1), plan when absent or unknown (the engine refuses an unknown one). */
    public ExecutionMode executionMode() {
        JsonElement value = root.get("execution");
        String name = value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        for (ExecutionMode mode : ExecutionMode.values()) {
            if (mode.jsonName().equals(name)) {
                return mode;
            }
        }
        return ExecutionMode.PLAN;
    }

    /** Plan, the default, removes the member; the other modes are written. */
    public void setExecutionMode(ExecutionMode mode) {
        if (mode == ExecutionMode.PLAN) {
            if (root.remove("execution") != null) {
                modified = true;
            }
            return;
        }
        if (mode == executionMode()) {
            return;
        }
        root.addProperty("execution", mode.jsonName());
        modified = true;
    }

    /** "conflictCheck" (spec conflict-check §1), quick when absent or unknown (the engine refuses an unknown one). */
    public ConflictCheck conflictCheck() {
        JsonElement value = root.get("conflictCheck");
        String name = value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        for (ConflictCheck check : ConflictCheck.values()) {
            if (check.jsonName().equals(name)) {
                return check;
            }
        }
        return ConflictCheck.QUICK;
    }

    /** Quick, the default, removes the member; the other levels are written. */
    public void setConflictCheck(ConflictCheck check) {
        if (check == ConflictCheck.QUICK) {
            if (root.remove("conflictCheck") != null) {
                modified = true;
            }
            return;
        }
        if (check == conflictCheck()) {
            return;
        }
        root.addProperty("conflictCheck", check.jsonName());
        modified = true;
    }

    /**
     * The "onMissingKey" of the out step's actionConfig, "error" when absent (as the file.write step): the consequence
     * the plan view's output pattern dialog shows (spec own-out-pattern §3).
     */
    public String outOnMissingKey() {
        JsonElement value = steps(Section.OUT).stream().findFirst()
                .map(step -> get(step, List.of("actionConfig", "onMissingKey"))).orElse(null);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "error";
    }

    // ---- run-end actions (spec run-end) ----

    /**
     * The outcomes a run-end action runs for ("when"), in the order of {@link #RUN_END_OUTCOMES}: those of the former
     * post-processing when absent; the unknown values are left out (the engine refuses them).
     */
    public List<String> runEndWhen(JsonObject step) {
        JsonElement when = step.get(WHEN);
        if (when == null || when.isJsonNull()) {
            return RunEndStepConfig.DEFAULT_WHEN;
        }
        List<String> values = stringList(when);
        return RUN_END_OUTCOMES.stream().filter(values::contains).toList();
    }

    /**
     * Writes the outcomes the action runs for, in the order of {@link #RUN_END_OUTCOMES}; the same ones as now rewrite
     * nothing.
     *
     * @throws IllegalArgumentException none, or an unknown one: nothing is written
     */
    public void setRunEndWhen(JsonObject step, Collection<String> outcomes) {
        if (outcomes.isEmpty() || !RUN_END_OUTCOMES.containsAll(outcomes)) {
            throw new IllegalArgumentException("run-end conditions " + outcomes);
        }
        List<String> ordered = RUN_END_OUTCOMES.stream().filter(outcomes::contains).toList();
        JsonArray when = new JsonArray();
        ordered.forEach(when::add);
        JsonElement current = step.get(WHEN);
        boolean same = current == null || current.isJsonNull()
                ? ordered.equals(RunEndStepConfig.DEFAULT_WHEN) // absent: those of the former post-processing
                : when.equals(current);
        if (same) {
            return;
        }
        step.add(WHEN, when);
        modified = true;
    }

    /** A "when" written in the file that the engine refuses: not a list, empty, or with an unknown value. */
    private static boolean invalidWhen(JsonObject step) {
        JsonElement when = step.get(WHEN);
        if (when == null || when.isJsonNull()) {
            return false;
        }
        if (!when.isJsonArray() || when.getAsJsonArray().isEmpty()) {
            return true;
        }
        for (JsonElement value : when.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !RUN_END_OUTCOMES.contains(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    // ---- grouping and archives (spec grouping-archives) ----

    /** The rules of the "grouping" block (each a preset or an own rule), live: empty when absent. */
    public List<JsonObject> groupingRules() {
        JsonArray rules = rulesArray();
        List<JsonObject> list = new ArrayList<>();
        if (rules != null) {
            rules.forEach(rule -> {
                if (rule.isJsonObject()) {
                    list.add(rule.getAsJsonObject());
                }
            });
        }
        return list;
    }

    private JsonArray rulesArray() {
        JsonElement block = root.get("grouping");
        JsonElement rules = block != null && block.isJsonObject() ? block.getAsJsonObject().get("rules") : null;
        return rules != null && rules.isJsonArray() ? rules.getAsJsonArray() : null;
    }

    /** Appends a rule, creating the block when absent. */
    public void addGroupingRule(JsonObject rule) {
        JsonArray rules = rulesArray();
        if (rules == null) {
            JsonObject block = new JsonObject();
            rules = new JsonArray();
            block.add("rules", rules);
            root.add("grouping", block);
        }
        rules.add(rule);
        modified = true;
    }

    /** Removes the rule at the index; the block goes with the last rule. */
    public void removeGroupingRule(int index) {
        JsonArray rules = rulesArray();
        if (rules == null || index < 0 || index >= rules.size()) {
            return;
        }
        rules.remove(index);
        if (rules.isEmpty()) {
            root.remove("grouping");
        }
        modified = true;
    }

    /** Moves the rule at {@code from} to the index {@code to} (the rules apply in order); out of range: nothing. */
    public void moveGroupingRule(int from, int to) {
        List<JsonElement> rules = new ArrayList<>();
        JsonArray array = rulesArray();
        if (array != null) {
            array.forEach(rules::add);
        }
        if (from == to || from < 0 || to < 0 || from >= rules.size() || to >= rules.size()) {
            return;
        }
        rules.add(to, rules.remove(from));
        JsonArray moved = new JsonArray();
        rules.forEach(moved::add);
        root.getAsJsonObject("grouping").add("rules", moved);
        modified = true;
    }

    /** The "archives" block; absent: nothing unfolded. */
    public ArchivesConfig archives() {
        JsonElement block = root.get("archives");
        if (block != null && block.isJsonObject()) {
            try {
                ArchivesConfig config = GsonUtil.getGson().fromJson(block, ArchivesConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (JsonParseException | IllegalStateException e) {
                // a block the editor cannot read counts as absent
            }
        }
        return new ArchivesConfig(null, null, null, null, null);
    }

    /** Writes the block, or removes it when the archives are not unfolded; empty patterns are not written. */
    public void setArchives(ArchivesConfig archives) {
        if (archives == null || !archives.isUnfold()) {
            if (root.remove("archives") != null) {
                modified = true;
            }
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("unfold", true);
        block.addProperty("nested", archives.isNested());
        block.addProperty("mergeFolder", archives.isMergeFolder());
        addPatterns(block, "include", archives.include());
        addPatterns(block, "exclude", archives.exclude());
        if (!block.equals(root.get("archives"))) {
            root.add("archives", block);
            modified = true;
        }
    }

    private static void addPatterns(JsonObject block, String name, List<String> patterns) {
        List<String> kept = patterns == null ? List.of()
                : patterns.stream().map(String::strip).filter(pattern -> !pattern.isEmpty()).toList();
        if (!kept.isEmpty()) {
            JsonArray array = new JsonArray();
            kept.forEach(array::add);
            block.add(name, array);
        }
    }

    /** The extensions the rules (the presets expanded) attach as companions, lower case, in order. */
    private List<String> companionExtensions() {
        Set<String> extensions = new LinkedHashSet<>();
        for (GroupingRuleConfig rule : expandedRules()) {
            if (rule.companions() != null) {
                rule.companions().keySet().forEach(ext -> extensions.add(ext.toLowerCase(Locale.ROOT)));
            }
        }
        return List.copyOf(extensions);
    }

    private List<GroupingRuleConfig> expandedRules() {
        List<GroupingRuleConfig> rules = new ArrayList<>();
        for (JsonObject rule : groupingRules()) {
            try {
                rules.add(GsonUtil.getGson().fromJson(rule, GroupingRuleConfig.class));
            } catch (JsonParseException | IllegalStateException e) {
                // a hand-edited rule the editor cannot read: skipped, so the user can still open the form and fix it
            }
        }
        return GroupingPresets.expand(new GroupingConfig(rules));
    }

    /**
     * The companion extensions of the rules that the include / exclude globs of a built-in file.read input step leave
     * out: the listing would never see those files, so they could not be attached.
     */
    public List<String> companionExtensionsExcludedByFileRead() {
        Set<String> excluded = new LinkedHashSet<>();
        for (JsonObject step : steps(Section.IN)) {
            if (isFileRead(step)) {
                for (String extension : companionExtensions()) {
                    if (!letsThrough(step, extension)) {
                        excluded.add(extension);
                    }
                }
            }
        }
        return List.copyOf(excluded);
    }

    /**
     * Lets the companion extensions through: adds them to the non-empty "include" of the file.read steps that leave
     * them out, and removes the "exclude" globs that target them.
     */
    public void allowCompanionExtensions() {
        for (JsonObject step : steps(Section.IN)) {
            JsonElement config = step.get("actionConfig");
            if (!isFileRead(step) || config == null || !config.isJsonObject()) {
                continue;
            }
            for (String extension : companionExtensions()) {
                if (!letsThrough(step, extension)) {
                    allow(config.getAsJsonObject(), extension);
                    modified = true;
                }
            }
        }
    }

    private static void allow(JsonObject config, String extension) {
        List<String> include = stringList(config.get("include"));
        if (!include.isEmpty() && !selected(include, List.of(), extension)) {
            // "**/" also matches at the first level (the engine adds that matcher); "*." would not go into folders
            config.getAsJsonArray("include").add("**/*." + extension);
        }
        JsonElement exclude = config.get("exclude");
        if (exclude != null && exclude.isJsonArray()) {
            JsonArray kept = new JsonArray();
            String own = "*." + extension;
            exclude.getAsJsonArray().forEach(glob -> {
                // only the globs that target exactly this extension go; any other one that happens to match stays
                String text = glob.isJsonPrimitive() ? glob.getAsString().toLowerCase(Locale.ROOT) : "";
                if (!text.equals(own) && !text.equals("**/" + own)) {
                    kept.add(glob);
                }
            });
            if (kept.isEmpty()) {
                config.remove("exclude");
            } else {
                config.add("exclude", kept);
            }
        }
    }

    /** Whether the include / exclude of the step let a file of this extension through. */
    private static boolean letsThrough(JsonObject step, String extension) {
        JsonElement config = step.get("actionConfig");
        if (config == null || !config.isJsonObject()) {
            return true;
        }
        return selected(stringList(config.getAsJsonObject().get("include")),
                stringList(config.getAsJsonObject().get("exclude")), extension);
    }

    /** The engine's selection of a file of this extension at the first level and in a folder (both must pass). */
    private static boolean selected(List<String> include, List<String> exclude, String extension) {
        for (Path probe : List.of(Path.of("file." + extension), Path.of("folder", "file." + extension))) {
            if (!include.isEmpty() && !matchesAny(include, probe) || matchesAny(exclude, probe)) {
                return false;
            }
        }
        return true;
    }

    /** Like the engine: case-insensitive globs on the relative path, "**&#47;x" also matching x at the first level. */
    private static boolean matchesAny(List<String> globs, Path probe) {
        for (String glob : globs) {
            String pattern = glob.toLowerCase(Locale.ROOT);
            try {
                if (FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(probe)
                        || pattern.startsWith("**/")
                        && FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)).matches(probe)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // a glob the listing refuses: it matches nothing here
            }
        }
        return false;
    }

    private static List<String> stringList(JsonElement element) {
        List<String> list = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            element.getAsJsonArray().forEach(item -> {
                if (item.isJsonPrimitive()) {
                    list.add(item.getAsString());
                }
            });
        }
        return list;
    }

    /** Whether a rule (a preset's included) is of the folder type while a file.read input does not go into folders. */
    public boolean folderRuleWithNonRecursiveFileRead() {
        return expandedRules().stream().anyMatch(rule -> "folder".equals(rule.type()))
                && steps(Section.IN).stream().anyMatch(step -> isFileRead(step) && isNonRecursive(step));
    }

    private static boolean isNonRecursive(JsonObject step) {
        JsonElement recursive = get(step, List.of("actionConfig", "recursive"));
        return recursive != null && recursive.isJsonPrimitive() && recursive.getAsJsonPrimitive().isBoolean()
                && !recursive.getAsBoolean();
    }

    /**
     * The warning of streaming with grouping rules (spec execution-mode §3): the files processed folder by folder,
     * or, with an input that is not the built-in file.read (which signals its folders), the run in the automatic
     * mode should that input not signal them. Empty when not streaming or without rule.
     */
    public Optional<StreamingNote> streamingGroupingWarning() {
        if (executionMode() != ExecutionMode.STREAMING || groupingRules().isEmpty()) {
            return Optional.empty();
        }
        List<String> others = steps(Section.IN).stream().filter(step -> !isFileRead(step))
                .map(PipelineDocument::actionName).toList();
        return Optional.of(others.isEmpty()
                ? new StreamingNote(ResourcesEngine.getString("editor.grouping.warning.streaming-by-folder"), false)
                : new StreamingNote(ResourcesEngine.getString("editor.grouping.warning.streaming-fallback",
                        String.join(", ", others)), true));
    }

    /**
     * The text of {@link #streamingGroupingWarning()}.
     *
     * @param fallback a warning (the run may fall back to the automatic mode), otherwise an information
     */
    public record StreamingNote(String text, boolean fallback) {
    }

    private static String actionName(JsonObject step) {
        JsonElement action = step.get("action");
        return action != null && action.isJsonPrimitive() ? action.getAsString() : "?";
    }

    private static boolean isFileRead(JsonObject step) {
        JsonElement action = step.get("action");
        JsonElement plugin = step.get("plugin");
        boolean embedded = plugin == null || plugin.isJsonNull()
                || plugin.isJsonPrimitive() && (plugin.getAsString().isBlank() || CatalogAction.EMBEDDED_PLUGIN.equals(plugin.getAsString()));
        return embedded && action != null && action.isJsonPrimitive() && FILE_READ.equals(action.getAsString());
    }

    // ---- sampling (spec pattern-helper §5) ----

    /** The JSON of the input steps, "" when absent: the sample is listed again when it changes. */
    public String samplingInJson() {
        return json(root.get(Section.IN.jsonName()));
    }

    /** The JSON of the analysis and action steps: the sample is analysed again when it changes. */
    public String samplingProcessingJson() {
        JsonObject processing = new JsonObject();
        processing.add(Section.ANALYZE.jsonName(), root.get(Section.ANALYZE.jsonName()));
        processing.add(Section.PROCESS.jsonName(), root.get(Section.PROCESS.jsonName()));
        return json(processing);
    }

    /**
     * The document read as the engine reads a pipeline, for the sampler. Read from a copy: Gson hands the
     * "actionConfig" elements over as they are, and the sampler reads them on another thread while the document
     * keeps being edited.
     *
     * @throws IllegalArgumentException the engine cannot read it (the message says why)
     */
    public PipelineConfig samplingConfig() {
        try {
            return GsonUtil.getGson().fromJson(root.deepCopy(), PipelineConfig.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    // ---- steps ----

    /** The step objects of the section, in order (live: changing one changes the document). */
    public List<JsonObject> steps(Section section) {
        JsonElement value = root.get(section.jsonName());
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (section == Section.OUT) {
            return List.of(value.getAsJsonObject());
        }
        List<JsonObject> steps = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> steps.add(element.getAsJsonObject()));
        return steps;
    }

    /** What a saved pipeline lacks to be useful: not blocking, the save only warns (same rules as the engine). */
    public enum Gap {
        /** no input step: there is nothing to list */
        NO_INPUT("pipeline.no-input"),
        /** neither output nor process step (analyse steps never count): nothing is done with the files */
        DOES_NOTHING("pipeline.does-nothing");

        private final String messageKey;

        Gap(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The localized sentence, the one the engine gives when it refuses such a pipeline. */
        public String message() {
            return ResourcesEngine.getString(messageKey);
        }
    }

    /** The parts missing from the pipeline, empty when it is complete. */
    public List<Gap> gaps() {
        List<Gap> gaps = new ArrayList<>();
        if (steps(Section.IN).isEmpty()) {
            gaps.add(Gap.NO_INPUT);
        }
        if (steps(Section.OUT).isEmpty() && steps(Section.PROCESS).isEmpty()) {
            gaps.add(Gap.DOES_NOTHING);
        }
        return gaps;
    }

    /** The output holds at most one step (spec desktop-ui §3). */
    public boolean canAdd(Section section) {
        return section != Section.OUT || steps(Section.OUT).isEmpty();
    }

    /**
     * Adds a step of this action at the end of the section: "plugin" (left out for an embedded action),
     * "action" and an empty "actionConfig".
     *
     * @throws IllegalStateException the output already has a step
     */
    public JsonObject addStep(Section section, CatalogAction action) {
        if (!canAdd(section)) {
            throw new IllegalStateException("the output already has a step");
        }
        JsonObject step = new JsonObject();
        if (!action.isEmbedded()) {
            step.addProperty("plugin", action.pluginName());
        }
        step.addProperty("action", action.actionCode());
        step.add("actionConfig", new JsonObject());
        if (section == Section.OUT) {
            root.add(section.jsonName(), step);
        } else {
            JsonElement value = root.get(section.jsonName());
            if (value == null || value.isJsonNull()) {
                value = new JsonArray();
                root.add(section.jsonName(), value);
            }
            value.getAsJsonArray().add(step);
        }
        modified = true;
        return step;
    }

    /** The output step never moves; a list step moves within its list. */
    public boolean canMove(Section section, int index, int delta) {
        int size = steps(section).size();
        int target = index + delta;
        return section != Section.OUT && index >= 0 && index < size && target >= 0 && target < size;
    }

    /** Moves the step by delta places (-1: up, +1: down); no effect when it cannot move. */
    public void moveStep(Section section, int index, int delta) {
        if (delta == 0 || !canMove(section, index, delta)) {
            return;
        }
        JsonArray array = root.getAsJsonArray(section.jsonName());
        JsonElement step = array.remove(index);
        List<JsonElement> elements = new ArrayList<>(array.asList());
        elements.add(index + delta, step);
        JsonArray moved = new JsonArray();
        elements.forEach(moved::add);
        root.add(section.jsonName(), moved); // same key: keeps its place among the members
        modified = true;
    }

    /** An emptied list stays in the file; the output step is removed with its "outStep" member. */
    public void removeStep(Section section, int index) {
        if (index < 0 || index >= steps(section).size()) {
            throw new IndexOutOfBoundsException(index);
        }
        if (section == Section.OUT) {
            root.remove(section.jsonName());
        } else {
            root.getAsJsonArray(section.jsonName()).remove(index);
        }
        modified = true;
    }

    // ---- exclusions ("always ignore" in the plan view) ----

    private static final String FILE_READ = "file.read";

    /**
     * What {@link #exclude} did.
     *
     * @param added     the globs added, in order (a glob already there is not added again)
     * @param uncovered the files no file.read step of the pipeline lists: nothing could exclude them
     */
    public record Exclusion(List<String> added, List<Path> uncovered) {
    }

    /**
     * Excludes these files for good: each one is added to the "exclude" globs of every file.read input step that
     * lists it (a file under its "path", at the first level only when "recursive" is false), as the glob that
     * matches this file alone ({@link FileReadAction#excludeGlob}).
     */
    public Exclusion exclude(Collection<Path> files) {
        List<String> added = new ArrayList<>();
        List<Path> uncovered = new ArrayList<>();
        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            boolean covered = false;
            for (JsonObject step : steps(Section.IN)) {
                Optional<Path> relative = listedBy(step, absolute);
                if (relative.isPresent()) {
                    covered = true;
                    String glob = FileReadAction.excludeGlob(relative.get());
                    if (addExclude(step, glob)) {
                        added.add(glob);
                    }
                }
            }
            if (!covered) {
                uncovered.add(file);
            }
        }
        return new Exclusion(List.copyOf(added), List.copyOf(uncovered));
    }

    /** The file relative to the "path" of this step when it is a file.read step that lists it, empty otherwise. */
    private static Optional<Path> listedBy(JsonObject step, Path file) {
        JsonElement path = get(step, List.of("actionConfig", "path"));
        if (!isFileRead(step) || path == null || !path.isJsonPrimitive() || path.getAsString().isBlank()) {
            return Optional.empty();
        }
        Path root;
        try {
            root = Path.of(path.getAsString()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        if (!file.startsWith(root) || file.equals(root)) {
            return Optional.empty();
        }
        Path relative = root.relativize(file);
        JsonElement recursive = get(step, List.of("actionConfig", "recursive"));
        boolean firstLevelOnly = recursive != null && recursive.isJsonPrimitive()
                && recursive.getAsJsonPrimitive().isBoolean() && !recursive.getAsBoolean();
        return firstLevelOnly && relative.getNameCount() > 1 ? Optional.empty() : Optional.of(relative);
    }

    /** Adds the glob to the step's "exclude" list (created when absent); false when it is already there. */
    private boolean addExclude(JsonObject step, String glob) {
        JsonObject config = step.getAsJsonObject("actionConfig");
        JsonElement exclude = config.get("exclude");
        if (exclude == null || !exclude.isJsonArray()) {
            exclude = new JsonArray();
            config.add("exclude", exclude);
        }
        for (JsonElement existing : exclude.getAsJsonArray()) {
            if (existing.isJsonPrimitive() && existing.getAsString().equalsIgnoreCase(glob)) {
                return false; // the globs are case-insensitive
            }
        }
        exclude.getAsJsonArray().add(glob);
        modified = true;
        return true;
    }

    // ---- fields ----

    /** An advanced field: labels in the UI bundle, "editor.advanced.&lt;name&gt;" and ".description". */
    private static ConfigField advanced(String name, FieldKind kind, ConfigField element) {
        return new ConfigField(name, name, kind, false, null, Set.of(),
                "editor.advanced." + name, "editor.advanced." + name + ".description", List.of(), List.of(), element);
    }

    /** The text of a schema field of the step's actionConfig: "" when absent, one line per list element. */
    public String configText(JsonObject step, ConfigField field) {
        return text(get(step, configPath(field)), field);
    }

    /**
     * Sets a schema field of the step's actionConfig from its text: blank removes it (and the objects it
     * leaves empty inside actionConfig); the same value as now rewrites nothing.
     *
     * @throws IllegalArgumentException not a number, not a boolean, a record or a list of records
     */
    public void setConfigText(JsonObject step, ConfigField field, String text) {
        setText(step, configPath(field), field, text, false);
    }

    /** The text of one of the {@link #ADVANCED_FIELDS} of the step. */
    public String advancedText(JsonObject step, ConfigField field) {
        return text(get(step, List.of(field.name())), field);
    }

    /**
     * Like {@link #setConfigText}; an integer stays within the int range (an Integer of PipelineStepConfig).
     *
     * @throws IllegalArgumentException also for a negative time limit (the engine refuses it)
     */
    public void setAdvancedText(JsonObject step, ConfigField field, String text) {
        if (TIMEOUT.equals(field.name()) && text != null && !text.isBlank()) {
            JsonElement value = valueOf(field, text, true); // a number, or the exception of one that is not
            if (value.getAsBigDecimal().signum() < 0) {
                throw new IllegalArgumentException(text);
            }
        }
        setText(step, List.of(field.name()), field, text, true);
    }

    /** The "Advanced" fields of a step of the section: {@link #ADVANCED_FIELDS}, but a time limit for a run-end action. */
    public static List<ConfigField> advancedFields(Section section) {
        return section == Section.RUN_END ? RUN_END_ADVANCED_FIELDS : ADVANCED_FIELDS;
    }

    /** The JSON value of a schema field of the step's actionConfig, null when absent. */
    public JsonElement configValue(JsonObject step, ConfigField field) {
        return get(step, configPath(field));
    }

    /** Pretty JSON of any element (a step, a list of records...), for a read-only display; "" for null. */
    public static String json(JsonElement element) {
        return element == null ? "" : GSON.toJson(element);
    }

    private static List<String> configPath(ConfigField field) {
        List<String> path = new ArrayList<>();
        path.add("actionConfig");
        path.addAll(List.of(field.path().split("\\.")));
        return path;
    }

    private static JsonElement get(JsonObject base, List<String> path) {
        JsonElement current = base;
        for (String member : path) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(member);
        }
        return current == null || current.isJsonNull() ? null : current;
    }

    private static String text(JsonElement value, ConfigField field) {
        if (value == null) {
            return "";
        }
        if (field.kind() == FieldKind.LIST && value.isJsonArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                lines.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
            }
            return String.join("\n", lines);
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private void setText(JsonObject base, List<String> path, ConfigField field, String text, boolean intRange) {
        String input = text == null ? "" : text;
        if (normalized(field, input).equals(normalized(field, text(get(base, path), field)))) {
            return; // the text shown, untouched: not even parsed (a value invalid in the file stays as is)
        }
        JsonElement value = valueOf(field, input, intRange);
        if (text(value, field).equals(text(get(base, path), field))) {
            return; // same value: nothing is rewritten ("8" stays 8, a list keeps its layout)
        }
        if (value == null) {
            remove(base, path);
        } else {
            JsonObject parent = base;
            for (String member : path.subList(0, path.size() - 1)) {
                JsonElement child = parent.get(member);
                if (child == null || !child.isJsonObject()) {
                    child = new JsonObject();
                    parent.add(member, child);
                }
                parent = child.getAsJsonObject();
            }
            parent.add(path.getLast(), value);
        }
        modified = true;
    }

    /** Removes the member and the objects it leaves empty, never the base nor its first level ("actionConfig"). */
    private static void remove(JsonObject base, List<String> path) {
        List<JsonObject> parents = new ArrayList<>();
        JsonObject parent = base;
        for (String member : path.subList(0, path.size() - 1)) {
            JsonElement child = parent.get(member);
            if (child == null || !child.isJsonObject()) {
                return;
            }
            parents.add(parent);
            parent = child.getAsJsonObject();
        }
        parent.remove(path.getLast());
        for (int i = parents.size() - 1; i >= 1 && parent.isEmpty(); i--) {
            parents.get(i).remove(path.get(i));
            parent = parents.get(i);
        }
    }

    /** A text as {@link #valueOf} reads it: stripped; for a list, its non-blank lines stripped. */
    private static String normalized(ConfigField field, String text) {
        if (field.kind() == FieldKind.LIST) {
            return String.join("\n", text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList());
        }
        return text.strip();
    }

    /**
     * The JSON value of a text, null for a blank one. An integer is within the long range: the schema does
     * not tell an int component from a long one (the engine refuses an int out of range when it loads the
     * configuration); within the int range when {@code intRange}.
     */
    private static JsonElement valueOf(ConfigField field, String text, boolean intRange) {
        if (field.kind() == FieldKind.LIST) {
            if (field.elementSchema() == null || field.elementSchema().kind() == FieldKind.RECORD
                    || field.elementSchema().kind() == FieldKind.LIST) {
                throw new IllegalArgumentException(field.path() + " cannot be edited as text");
            }
            JsonArray array = new JsonArray();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    array.add(valueOf(field.elementSchema(), line.strip(), intRange));
                }
            }
            return array.isEmpty() ? null : array;
        }
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        return switch (field.kind()) {
            case BOOLEAN -> switch (value.toLowerCase(Locale.ROOT)) {
                case "true" -> new JsonPrimitive(true);
                case "false" -> new JsonPrimitive(false);
                default -> throw new IllegalArgumentException(value);
            };
            case INTEGER -> { // "8" or "8.0"
                try {
                    BigDecimal number = new BigDecimal(value);
                    yield new JsonPrimitive(intRange ? number.intValueExact() : number.longValueExact());
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException(value, e);
                }
            }
            case DECIMAL -> {
                try {
                    yield new JsonPrimitive(new BigDecimal(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(value, e);
                }
            }
            case RECORD, LIST -> throw new IllegalArgumentException(field.path() + " cannot be edited as text");
            case STRING, PATH, ENUM -> new JsonPrimitive(value);
        };
    }

    // ---- validation ----

    /**
     * The required fields left empty (spec desktop-ui §3), for the steps whose action is loaded: a required
     * field of a record counts only when the record is present. A step without action is a problem too
     * (field path "action"), and so are the conditions of a run-end action the engine refuses (field path "when").
     */
    public List<Problem> validate(StepCatalog catalog) {
        List<Problem> problems = new ArrayList<>();
        for (Section section : Section.values()) {
            List<JsonObject> steps = steps(section);
            for (int i = 0; i < steps.size(); i++) {
                JsonObject step = steps.get(i);
                JsonElement action = step.get("action");
                if (action == null || !action.isJsonPrimitive() || action.getAsString().isBlank()) {
                    problems.add(new Problem(section, i, "action"));
                    continue;
                }
                int index = i;
                catalog.find(section, step).flatMap(CatalogAction::configSchema).ifPresent(schema ->
                        checkRequired(step, schema.fields(), section, index, problems));
                if (section == Section.RUN_END && invalidWhen(step)) {
                    problems.add(new Problem(section, i, WHEN));
                }
            }
        }
        return problems;
    }

    /** A reference to a secret not defined on this machine: the step at index of the section, the secret's name. */
    public record UnknownSecret(Section section, int index, String name) {
    }

    /**
     * The secrets the action configurations refer to ({@code ${secret:name}} in any text value) that are not among
     * these names: not blocking (a pipeline may be shared with a machine where they are defined), the save only warns.
     */
    public List<UnknownSecret> unknownSecrets(Collection<String> definedNames) {
        List<UnknownSecret> unknown = new ArrayList<>();
        for (Section section : Section.values()) {
            List<JsonObject> steps = steps(section);
            for (int i = 0; i < steps.size(); i++) {
                Set<String> names = new LinkedHashSet<>();
                collectSecretNames(steps.get(i).get("actionConfig"), names);
                for (String name : names) {
                    if (!definedNames.contains(name)) {
                        unknown.add(new UnknownSecret(section, i, name));
                    }
                }
            }
        }
        return unknown;
    }

    private static void collectSecretNames(JsonElement element, Set<String> names) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(member -> collectSecretNames(member.getValue(), names));
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(item -> collectSecretNames(item, names));
        } else if (element.getAsJsonPrimitive().isString()) {
            names.addAll(SecretField.secretNames(element.getAsString()));
        }
    }

    private static void checkRequired(JsonObject step, List<ConfigField> fields, Section section, int index,
                                      List<Problem> problems) {
        for (ConfigField field : fields) {
            JsonElement value = get(step, configPath(field));
            boolean empty = value == null || (value.isJsonPrimitive() && value.getAsString().isBlank())
                    || (value.isJsonArray() && value.getAsJsonArray().isEmpty());
            if (field.required() && empty) {
                problems.add(new Problem(section, index, field.path()));
            }
            if (field.kind() == FieldKind.RECORD && value != null && value.isJsonObject()) {
                checkRequired(step, field.children(), section, index, problems);
            }
        }
    }
}
