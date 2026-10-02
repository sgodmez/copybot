package com.copybot.ui.model;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigField;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
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
        OUT("outStep", StepType.OUT);

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

    /** The resume modes, as written in the pipeline ("resume.mode"). */
    public static final List<String> RESUME_MODES = List.of("none", "state", "destination", "stateThenDestination");

    /** The mode of a "resume" block without mode (see {@code ResumeConfig.effectiveMode}). */
    private static final String DEFAULT_RESUME_MODE = "stateThenDestination";

    /** A required field left empty: the step at index of the section, the field path in its actionConfig. */
    public record Problem(Section section, int index, String fieldPath) {
    }

    /** The "Advanced" fields of every step (spec desktop-ui §3), members of the step object itself. */
    public static final List<ConfigField> ADVANCED_FIELDS = List.of(
            advanced("maxConcurrency", FieldKind.INTEGER, null),
            advanced("resources", FieldKind.LIST, advanced("resources", FieldKind.STRING, null)),
            advanced("priority", FieldKind.INTEGER, null),
            advanced("version", FieldKind.STRING, null));

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
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject root = tree.getAsJsonObject();
        for (Section section : Section.values()) {
            JsonElement value = root.get(section.jsonName());
            boolean valid = value == null || value.isJsonNull()
                    || (section == Section.OUT ? value.isJsonObject() : isArrayOfObjects(value));
            if (!valid) {
                throw new IllegalArgumentException("\"" + section.jsonName() + "\" is not made of step objects");
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

    public boolean startProcessingWhileListing() {
        return bool(root, "startProcessingWhileListing");
    }

    /** true is written; false removes a member that was true (false is the engine default). */
    public void setStartProcessingWhileListing(boolean value) {
        if (value == startProcessingWhileListing()) {
            return;
        }
        if (value) {
            root.addProperty("startProcessingWhileListing", true);
        } else {
            root.remove("startProcessingWhileListing");
        }
        modified = true;
    }

    /** "ui.autoExecute" (spec desktop-ui §2), false when absent. */
    public boolean autoExecute() {
        JsonElement ui = root.get("ui");
        return ui != null && ui.isJsonObject() && bool(ui.getAsJsonObject(), "autoExecute");
    }

    /** true is written; false removes it, and the "ui" block when nothing else is left in it. */
    public void setAutoExecute(boolean value) {
        if (value == autoExecute()) {
            return;
        }
        JsonElement ui = root.get("ui");
        if (value) {
            if (ui == null || !ui.isJsonObject()) {
                ui = new JsonObject();
                root.add("ui", ui);
            }
            ui.getAsJsonObject().addProperty("autoExecute", true);
        } else {
            JsonObject uiObject = ui.getAsJsonObject();
            uiObject.remove("autoExecute");
            if (uiObject.isEmpty()) {
                root.remove("ui");
            }
        }
        modified = true;
    }

    /** The effective resume mode: empty without "resume" block, stateThenDestination for a block without mode. */
    public Optional<String> resumeMode() {
        JsonElement resume = root.get("resume");
        if (resume == null || !resume.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement mode = resume.getAsJsonObject().get("mode");
        return Optional.of(mode != null && mode.isJsonPrimitive() ? mode.getAsString() : DEFAULT_RESUME_MODE);
    }

    /**
     * @param mode one of {@link #RESUME_MODES}, null for no resume: the block is removed, unless it holds
     *             other members (then its mode becomes "none")
     */
    public void setResumeMode(String mode) {
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

    private static boolean bool(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
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

    /** Like {@link #setConfigText}; an integer stays within the int range (an Integer of PipelineStepConfig). */
    public void setAdvancedText(JsonObject step, ConfigField field, String text) {
        setText(step, List.of(field.name()), field, text, true);
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
     * (field path "action").
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
            }
        }
        return problems;
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
