package com.copybot.ui;

import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginCatalog.FailedPlugin;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.sample.PipelineSampler;
import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleSession;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PatternHelperModel;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineDocument.Problem;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.Views;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The generic pipeline editor (spec desktop-ui §3, variant A): a tree of the sections on the left, the form
 * of the selected element on the right. It edits a {@link PipelineDocument}; only the fields it shows are
 * rewritten. The pipeline file is read and written off the JavaFX thread (it may be on a network share).
 */
public class EditorController {

    enum Kind { PIPELINE, SECTION, STEP }

    /** A node of the tree: the pipeline, a section, or the step at index of a section. */
    record EditorNode(Kind kind, Section section, int index) {
    }

    private static final EditorNode PIPELINE_NODE = new EditorNode(Kind.PIPELINE, null, -1);

    @FXML private TreeView<EditorNode> tree;
    @FXML private Button addButton;
    @FXML private Button upButton;
    @FXML private Button downButton;
    @FXML private Button removeButton;
    @FXML private VBox formBox;
    @FXML private Button showJsonButton;
    @FXML private Button saveAsButton;
    @FXML private Button saveButton;

    private Stage stage;
    private PipelineDocument document;
    private Path path;
    /**
     * The file read as non-strict JSON (comments...) or with duplicate members, until the user agreed to
     * rewrite it (null then, or when nothing of the file is lost): the first save over it warns first.
     */
    private Path lossyFile;
    private StepCatalog catalog;
    private Consumer<Path> onSaved;
    /** A save runs in the background: the window is disabled and cannot be closed meanwhile. */
    private boolean saving;
    /** The controls of the current form whose text does not parse: no save meanwhile. */
    private final ConfigForm.Invalid invalid = new ConfigForm.Invalid();

    /** The sample of the edited pipeline (spec pattern-helper §5), opened on the first out form shown. */
    private SampleSession sampleSession;
    private Sample sample;
    /** The JSON the sample was taken from, null before the first one (and after a failed one). */
    private String sampledIn;
    private String sampledProcessing;
    /** A sample is being taken in the background. */
    private boolean sampling;
    /** Set when the window closes: late background callbacks must not touch it. */
    private boolean closed;
    /** The helper of the out form shown, null when another form is shown. */
    private PatternHelper patternHelper;
    /** What the helper keeps across rebuilt forms while the editor is open (panel expanded, tested files). */
    private final PatternHelper.State helperState = new PatternHelper.State();
    /** Analyses the files the user chose to test, one at a time, apart from the sample's session. */
    private SampleSession pinnedSession;
    /** Tested files waiting for their analysis, and whether one runs. */
    private final Set<Path> pinnedQueue = new LinkedHashSet<>();
    private boolean pinnedRunning;

    /** A pipeline file is being read for {@link #open} (JavaFX thread only): another open is ignored. */
    private static boolean loading;

    /**
     * Opens the editor, modal, on this pipeline file or on a new pipeline (path null). Call it on the
     * JavaFX thread; the file is read in the background, the window opens once it is read (an error popup
     * otherwise), so this method returns at once. While a file is being read, another call is ignored (a
     * double click on the trigger opens one editor).
     *
     * @param onSaved called with the file once it is saved and the window closed (on the JavaFX thread)
     */
    public static void open(Window owner, Path path, Consumer<Path> onSaved) {
        if (loading) {
            return;
        }
        Path file = path == null ? null : path.toAbsolutePath().normalize();
        Scene ownerScene = owner == null ? null : owner.getScene();
        if (ownerScene != null) {
            ownerScene.setCursor(Cursor.WAIT);
        }
        loading = true;
        Locale language = Locale.getDefault();
        // the catalog (plugin instances, bundles) is built off the JavaFX thread too
        background(() -> new Loaded(file == null ? PipelineDocument.empty() : PipelineDocument.load(file),
                        new StepCatalog(PluginEngine.catalog())),
                loaded -> {
                    loading = false;
                    resetCursor(ownerScene);
                    if (!Locale.getDefault().equals(language)) {
                        // the language changed meanwhile (Preferences): the catalog's names are in the old one
                        open(owner, path, onSaved);
                        return;
                    }
                    show(owner, loaded, file, onSaved);
                },
                e -> {
                    loading = false;
                    resetCursor(ownerScene);
                    PopinUtil.showError(e);
                });
    }

    /** What the background part of {@link #open} prepares. */
    private record Loaded(PipelineDocument document, StepCatalog catalog) {
    }

    /** Builds and shows the window; a failure (FXML...) is shown in an error popup. */
    private static void show(Window owner, Loaded loaded, Path path, Consumer<Path> onSaved) {
        try {
            Views.Loaded<EditorController> view = Views.load("editor-view.fxml");
            Stage stage = new Stage();
            stage.initOwner(owner);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setScene(new Scene(view.root()));
            view.controller().init(stage, loaded.document(), loaded.catalog(), path, onSaved);
            stage.show();
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
    }

    private static void resetCursor(Scene scene) {
        if (scene != null) {
            scene.setCursor(null);
        }
    }

    /**
     * Runs the work on a daemon thread, then its result or its failure on the JavaFX thread. Any throwable
     * reaches onError (an Error wrapped), so the caller always gets one of the two callbacks.
     */
    private static <T> void background(Callable<T> work, Consumer<T> onDone, Consumer<Exception> onError) {
        Thread.ofPlatform().daemon().name("copybot-editor-io").start(() -> {
            T result;
            try {
                result = work.call();
            } catch (Throwable t) {
                Exception e = t instanceof Exception exception ? exception : new IllegalStateException(t.toString(), t);
                Platform.runLater(() -> onError.accept(e));
                return;
            }
            Platform.runLater(() -> onDone.accept(result));
        });
    }

    @FXML
    public void initialize() {
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new TreeCell<>() {
            @Override
            protected void updateItem(EditorNode node, boolean empty) {
                super.updateItem(node, empty);
                setText(empty || node == null ? null : label(node));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            showForm(item == null ? null : item.getValue());
            updateButtons();
        });
    }

    void init(Stage stage, PipelineDocument document, StepCatalog catalog, Path path, Consumer<Path> onSaved) {
        this.stage = stage;
        this.document = document;
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.lossyFile = document.isLenient() || !document.duplicateKeys().isEmpty() ? this.path : null;
        this.onSaved = onSaved;
        this.catalog = catalog;
        stage.setOnCloseRequest(e -> {
            if (saving || (document.isModified() && !confirm(ResourcesEngine.getString("editor.discard")))) {
                e.consume();
            }
        });
        // both ways out (an accepted close request, the close after a save): the sample is no longer wanted
        stage.setOnHidden(e -> {
            closed = true;
            cancelSample();
            if (pinnedSession != null) {
                pinnedSession.cancel();
            }
        });
        // no default button (Enter in a field must not save): Ctrl+S (Cmd+S) instead
        shortcut(saveButton, "editor.save.tooltip",
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), this::onSaveClick);
        shortcut(saveAsButton, "editor.save-as.tooltip",
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onSaveAsClick);
        shortcut(showJsonButton, "editor.show-json.tooltip",
                new KeyCodeCombination(KeyCode.J, KeyCombination.SHORTCUT_DOWN), this::onShowJsonClick);
        updateTitle();
        rebuildTree(PIPELINE_NODE);
    }

    /** The accelerator of the window, named in the tooltip of its button ("Ctrl+Shift+S"). */
    private void shortcut(Button button, String tooltipKey, KeyCombination keys, Runnable action) {
        stage.getScene().getAccelerators().put(keys, action);
        button.setTooltip(new Tooltip(ResourcesEngine.getString(tooltipKey, keys.getDisplayText())));
    }

    private void updateTitle() {
        String name = path == null ? ResourcesEngine.getString("editor.untitled") : RecentPipelines.displayName(path);
        stage.setTitle(ResourcesEngine.getString("editor.title", name));
    }

    // ---- tree ----

    private void rebuildTree(EditorNode select) {
        TreeItem<EditorNode> root = new TreeItem<>();
        TreeItem<EditorNode> selected = new TreeItem<>(PIPELINE_NODE);
        root.getChildren().add(selected);
        for (Section section : Section.values()) {
            EditorNode sectionNode = new EditorNode(Kind.SECTION, section, -1);
            TreeItem<EditorNode> sectionItem = new TreeItem<>(sectionNode);
            sectionItem.setExpanded(true);
            if (sectionNode.equals(select)) {
                selected = sectionItem;
            }
            for (int i = 0; i < document.steps(section).size(); i++) {
                TreeItem<EditorNode> stepItem = new TreeItem<>(new EditorNode(Kind.STEP, section, i));
                if (stepItem.getValue().equals(select)) {
                    selected = stepItem;
                }
                sectionItem.getChildren().add(stepItem);
            }
            root.getChildren().add(sectionItem);
        }
        tree.setRoot(root);
        tree.getSelectionModel().select(selected);
    }

    private String label(EditorNode node) {
        return switch (node.kind()) {
            case PIPELINE -> ResourcesEngine.getString("editor.section.PIPELINE");
            case SECTION -> ResourcesEngine.getString("editor.section." + node.section().name());
            case STEP -> {
                JsonObject step = step(node);
                yield catalog.find(node.section(), step).map(CatalogAction::name)
                        .orElseGet(() -> member(step, "action") + " (?)");
            }
        };
    }

    private JsonObject step(EditorNode node) {
        return document.steps(node.section()).get(node.index());
    }

    private static String member(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private EditorNode selectedNode() {
        TreeItem<EditorNode> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private void updateButtons() {
        EditorNode node = selectedNode();
        boolean step = node != null && node.kind() == Kind.STEP;
        addButton.setDisable(node == null || node.section() == null || !document.canAdd(node.section()));
        upButton.setDisable(!step || !document.canMove(node.section(), node.index(), -1));
        downButton.setDisable(!step || !document.canMove(node.section(), node.index(), 1));
        removeButton.setDisable(!step);
    }

    @FXML
    protected void onAddClick() {
        Section section = selectedNode().section();
        chooseAction(section).ifPresent(action -> {
            document.addStep(section, action);
            rebuildTree(new EditorNode(Kind.STEP, section, document.steps(section).size() - 1));
        });
    }

    @FXML
    protected void onUpClick() {
        move(-1);
    }

    @FXML
    protected void onDownClick() {
        move(1);
    }

    private void move(int delta) {
        EditorNode node = selectedNode();
        document.moveStep(node.section(), node.index(), delta);
        rebuildTree(new EditorNode(Kind.STEP, node.section(), node.index() + delta));
    }

    @FXML
    protected void onRemoveClick() {
        EditorNode node = selectedNode();
        document.removeStep(node.section(), node.index());
        rebuildTree(new EditorNode(Kind.SECTION, node.section(), -1));
    }

    /** The loaded actions of the section's step type, name and description localized. */
    private Optional<CatalogAction> chooseAction(Section section) {
        List<CatalogAction> actions = catalog.forSection(section);
        Dialog<CatalogAction> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(ResourcesEngine.getString("editor.add.title"));
        ListView<CatalogAction> list = new ListView<>();
        list.getItems().setAll(actions);
        list.setPlaceholder(new Label(ResourcesEngine.getString("editor.add.empty")));
        list.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(CatalogAction action, boolean empty) {
                super.updateItem(action, empty);
                if (empty || action == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(action.name());
                name.setStyle("-fx-font-weight: bold;");
                Label description = new Label(action.description());
                description.setWrapText(true);
                setGraphic(new VBox(2, name, description));
            }
        });
        list.setPrefSize(460, 300);
        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(button -> button == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    // ---- forms ----

    private void showForm(EditorNode node) {
        patternHelper = null; // it belongs to the form being replaced
        formBox.getChildren().clear();
        invalid.clear();
        if (node == null || node.kind() == Kind.SECTION) {
            return;
        }
        if (node.kind() == Kind.PIPELINE) {
            pipelineForm();
        } else {
            stepForm(node);
        }
    }

    /**
     * execution, resume.mode, then for a destination-based mode resume.destinationCheck and resume.destinationMatch
     * (spec desktop-ui §3, spec execution-mode §5).
     */
    private void pipelineForm() {
        ComboBox<ExecutionMode> execution = new ComboBox<>();
        execution.getItems().addAll(ExecutionMode.values());
        execution.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExecutionMode mode) {
                return mode == null ? "" : ResourcesEngine.getString("editor.execution." + mode.jsonName());
            }

            @Override
            public ExecutionMode fromString(String s) {
                return null; // not editable
            }
        });
        execution.setValue(document.executionMode());
        Label executionWarning = new Label("⚠ " + ResourcesEngine.getString("editor.execution.streaming-dichotomy"));
        executionWarning.setWrapText(true);
        executionWarning.setStyle("-fx-text-fill: #c87f0a;");

        ComboBox<String> destinationCheck = optionBox("editor.destination-check.", PipelineDocument.DESTINATION_CHECKS);
        ComboBox<String> destinationMatch = optionBox("editor.destination-match.", PipelineDocument.DESTINATION_MATCHES);

        ComboBox<String> resumeMode = new ComboBox<>();
        resumeMode.getItems().add("");
        resumeMode.getItems().addAll(PipelineDocument.RESUME_MODES);
        resumeMode.setConverter(new StringConverter<>() {
            @Override
            public String toString(String mode) {
                return PlanViewModel.resumeModeText(mode == null || mode.isEmpty() ? null : mode);
            }

            @Override
            public String fromString(String s) {
                return s; // not editable
            }
        });
        resumeMode.setValue(document.resumeMode().orElse(""));

        // the destination options only mean something for the destination-based modes
        Runnable sync = () -> {
            boolean destination = destinationBased(document.resumeMode().orElse(null));
            destinationCheck.setDisable(!destination);
            destinationMatch.setDisable(!destination);
            destinationCheck.setValue(document.destinationCheck());
            destinationMatch.setValue(document.destinationMatch());
            boolean warn = destination && document.executionMode() == ExecutionMode.STREAMING
                    && PipelineDocument.DESTINATION_CHECKS.getFirst().equals(document.destinationCheck());
            executionWarning.setVisible(warn);
            executionWarning.setManaged(warn);
        };
        sync.run();
        execution.valueProperty().addListener((obs, old, mode) -> {
            document.setExecutionMode(mode == null ? ExecutionMode.PLAN : mode);
            sync.run();
        });
        resumeMode.valueProperty().addListener((obs, old, mode) -> {
            document.setResumeMode(mode == null || mode.isEmpty() ? null : mode);
            sync.run();
        });
        destinationCheck.valueProperty().addListener((obs, old, check) -> {
            if (check != null && !destinationCheck.isDisabled() && !check.equals(document.destinationCheck())) {
                document.setDestinationCheck(check);
                sync.run();
            }
        });
        destinationMatch.valueProperty().addListener((obs, old, match) -> {
            if (match != null && !destinationMatch.isDisabled() && !match.equals(document.destinationMatch())) {
                document.setDestinationMatch(match);
            }
        });

        // how far the plan checks the targets that already exist (spec conflict-check §5)
        ComboBox<ConflictCheck> conflictCheck = new ComboBox<>();
        conflictCheck.getItems().addAll(ConflictCheck.values());
        conflictCheck.setConverter(new StringConverter<>() {
            @Override
            public String toString(ConflictCheck check) {
                return check == null ? "" : ResourcesEngine.getString("editor.conflict-check." + check.jsonName());
            }

            @Override
            public ConflictCheck fromString(String s) {
                return null; // not editable
            }
        });
        conflictCheck.setValue(document.conflictCheck());
        conflictCheck.valueProperty().addListener((obs, old, check) ->
                document.setConflictCheck(check == null ? ConflictCheck.QUICK : check));

        formBox.getChildren().addAll(title(ResourcesEngine.getString("editor.section.PIPELINE")),
                new VBox(3, new Label(ResourcesEngine.getString("editor.execution")), execution, executionWarning),
                new VBox(3, new Label(ResourcesEngine.getString("editor.conflict-check")), conflictCheck),
                new VBox(3, new Label(ResourcesEngine.getString("editor.resume-mode")), resumeMode),
                new VBox(3, new Label(ResourcesEngine.getString("editor.destination-check")), destinationCheck),
                new VBox(3, new Label(ResourcesEngine.getString("editor.destination-match")), destinationMatch));
    }

    /** The resume modes that look at the destination: destination, and stateThenDestination without cursor. */
    private static boolean destinationBased(String resumeMode) {
        return "destination".equals(resumeMode) || "stateThenDestination".equals(resumeMode);
    }

    /** A list of the values of a resume option, each shown by its label (prefix + value). */
    private static ComboBox<String> optionBox(String labelPrefix, List<String> values) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().addAll(values);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : ResourcesEngine.getString(labelPrefix + value);
            }

            @Override
            public String fromString(String s) {
                return s; // not editable
            }
        });
        return box;
    }

    /**
     * The generated form of the action's schema, then "Advanced"; read-only when the plugin is missing or
     * when the version the step resolves to failed (its actions cannot be listed).
     */
    private void stepForm(EditorNode node) {
        JsonObject step = step(node);
        Optional<CatalogAction> action = catalog.find(node.section(), step);
        if (action.isEmpty()) {
            String plugin = member(step, "plugin");
            String message = catalog.failedPlugin(step)
                    .map(failed -> ResourcesEngine.getString("editor.plugin-failed", failed.pluginVersion() == null
                            ? failed.pluginName() : failed.pluginName() + " " + failed.pluginVersion()))
                    .orElseGet(() -> ResourcesEngine.getString("editor.plugin-not-found",
                            plugin.isEmpty() ? CatalogAction.EMBEDDED_PLUGIN : plugin));
            formBox.getChildren().addAll(title(member(step, "action")), new Label(message),
                    readOnly(PipelineDocument.json(step)));
            return;
        }
        CatalogAction catalogAction = action.get();
        formBox.getChildren().add(title(catalogAction.name()));
        if (!catalogAction.description().isEmpty()) {
            Label description = new Label(catalogAction.description());
            description.setWrapText(true);
            formBox.getChildren().add(description);
        }
        Optional<ConfigSchema> schema = catalogAction.configSchema();
        if (schema.isPresent()) {
            formBox.getChildren().add(ConfigForm.build(schema.get().fields(),
                    configAccess(step, catalogAction, node.section() == Section.OUT), stage, invalid));
        } else {
            formBox.getChildren().addAll(new Label(ResourcesEngine.getString("editor.no-schema")),
                    readOnly(PipelineDocument.json(step.get("actionConfig"))));
        }
        TitledPane advanced = new TitledPane(ResourcesEngine.getString("editor.advanced"),
                ConfigForm.build(PipelineDocument.ADVANCED_FIELDS, advancedAccess(node, step), stage, invalid));
        advanced.setExpanded(false);
        formBox.getChildren().add(advanced);
    }

    /** @param outStep the step is the output one: its pattern field gets the sample-based helper */
    private ConfigForm.Access configAccess(JsonObject step, CatalogAction action, boolean outStep) {
        return new ConfigForm.Access() {
            @Override
            public String text(ConfigField field) {
                return document.configText(step, field);
            }

            @Override
            public void setText(ConfigField field, String text) {
                document.setConfigText(step, field, text);
                if (patternHelper != null) {
                    patternHelper.refresh(); // "onMissingKey" may have changed: the effects shown follow it
                }
            }

            @Override
            public ConfigForm.Helper patternHelper(ConfigField field, TextInputControl input) {
                if (!outStep) {
                    return null;
                }
                ConfigField onMissingKey = action.configSchema().flatMap(schema -> schema.field("onMissingKey")).orElse(null);
                patternHelper = new PatternHelper(input,
                        () -> onMissingKey == null ? null : document.configText(step, onMissingKey),
                        helperState, helperActions());
                if (sample != null) {
                    patternHelper.showSample(sample);
                }
                ensureSample();
                return new ConfigForm.Helper(patternHelper.pickerButton(), patternHelper);
            }

            @Override
            public String label(ConfigField field) {
                return action.label(field);
            }

            @Override
            public String valueLabel(ConfigField field, String value) {
                return action.valueLabel(field, value);
            }

            @Override
            public String description(ConfigField field) {
                return action.description(field);
            }

            @Override
            public String json(ConfigField field) {
                return PipelineDocument.json(document.configValue(step, field));
            }
        };
    }

    // ---- sample (spec pattern-helper §5) ----

    /**
     * Starts a sample when none matches the steps being edited: listed again when the input steps changed,
     * analysed again when only the analysis or action steps did. While one runs, the helper just shows it loading.
     */
    private void ensureSample() {
        if (sampling) {
            if (patternHelper != null) {
                patternHelper.showLoading();
            }
            return;
        }
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();
        PatternHelperModel.Rerun rerun = PatternHelperModel.rerun(sampledIn, sampledProcessing, in, processing);
        if (rerun == PatternHelperModel.Rerun.NONE) {
            return;
        }
        PipelineConfig config;
        try {
            config = document.samplingConfig();
        } catch (IllegalArgumentException e) {
            showSampleResult(Sample.failed(String.valueOf(e.getMessage())), in, processing);
            return;
        }
        if (sampleSession == null) {
            sampleSession = PipelineSampler.open();
        }
        SampleSession session = sampleSession;
        sampling = true;
        if (patternHelper != null) {
            patternHelper.showLoading();
        }
        background(() -> rerun == PatternHelperModel.Rerun.LIST ? session.list(config) : session.analyse(config),
                result -> sampleDone(result, in, processing),
                e -> sampleDone(Sample.failed(String.valueOf(e.getMessage())), in, processing));
    }

    private void sampleDone(Sample result, String in, String processing) {
        sampling = false;
        if (closed) {
            return;
        }
        showSampleResult(result, in, processing);
        if (result.failure().isEmpty()) {
            // analysed with the steps of this sample: the tested files follow
            // over a copy: analysePinned replaces the entries of the list
            List.copyOf(helperState.pinned).forEach(pinned -> analysePinned(pinned.file()));
        }
        if (patternHelper != null && result.failure().isEmpty()) {
            // the steps may have been edited while it ran (the out form shown again meanwhile): catch up
            ensureSample();
        }
    }

    // ---- tested files (spec pattern-helper §5) ----

    private PatternHelper.Actions helperActions() {
        return new PatternHelper.Actions() {
            @Override
            public void retry() {
                resample();
            }

            @Override
            public void cancel() {
                cancelSample();
            }

            @Override
            public void test(Path file) {
                Path normalized = file.toAbsolutePath().normalize();
                helperState.pinned.removeIf(pinned -> pinned.file().equals(normalized));
                helperState.pinned.addFirst(new PatternHelper.Pinned(normalized, null));
                analysePinned(normalized);
            }

            @Override
            public void unpin(Path file) {
                helperState.pinned.removeIf(pinned -> pinned.file().equals(file));
                pinnedQueue.remove(file);
                refreshHelper();
            }

            @Override
            public Path sourceFolder() {
                return EditorController.this.sourceFolder();
            }
        };
    }

    /** The path of the first input step's actionConfig when it is an existing folder, else null. */
    private Path sourceFolder() {
        List<JsonObject> in = document.steps(Section.IN);
        if (in.isEmpty() || !(in.getFirst().get("actionConfig") instanceof JsonObject config)
                || !(config.get("path") instanceof JsonPrimitive path) || !path.isString()) {
            return null;
        }
        try {
            Path folder = Path.of(path.getAsString());
            return Files.isDirectory(folder) ? folder : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Shows the file as being analysed and queues its analysis (with the steps current when it starts). */
    private void analysePinned(Path file) {
        setPinned(file, null);
        pinnedQueue.add(file);
        refreshHelper();
        nextPinned();
    }

    /** Runs the next queued analysis unless one runs: the session runs one operation at a time. */
    private void nextPinned() {
        if (pinnedRunning || pinnedQueue.isEmpty() || closed) {
            return;
        }
        Path file = pinnedQueue.iterator().next();
        pinnedQueue.remove(file);
        PipelineConfig config;
        try {
            config = document.samplingConfig();
        } catch (IllegalArgumentException e) {
            setPinned(file, Sample.failed(String.valueOf(e.getMessage())));
            refreshHelper();
            nextPinned();
            return;
        }
        if (pinnedSession == null) {
            pinnedSession = PipelineSampler.open();
        }
        SampleSession session = pinnedSession;
        pinnedRunning = true;
        background(() -> session.analyseFile(config, file),
                result -> pinnedDone(file, result),
                e -> pinnedDone(file, Sample.failed(String.valueOf(e.getMessage()))));
    }

    private void pinnedDone(Path file, Sample result) {
        pinnedRunning = false;
        if (closed) {
            return;
        }
        if (!pinnedQueue.contains(file)) { // queued again meanwhile: the newer analysis decides
            setPinned(file, result);
        }
        refreshHelper();
        nextPinned();
    }

    /** Replaces the analysis of a tested file still pinned (an unpinned one is left alone). */
    private void setPinned(Path file, Sample result) {
        List<PatternHelper.Pinned> pinned = helperState.pinned;
        for (int i = 0; i < pinned.size(); i++) {
            if (pinned.get(i).file().equals(file)) {
                pinned.set(i, new PatternHelper.Pinned(file, result));
            }
        }
    }

    private void refreshHelper() {
        if (patternHelper != null) {
            patternHelper.refresh();
        }
    }

    private void showSampleResult(Sample result, String in, String processing) {
        sample = result;
        // a failed (or cancelled) sample is not remembered as taken: the next display of the out form tries again
        sampledIn = result.failure().isPresent() ? null : in;
        sampledProcessing = result.failure().isPresent() ? null : processing;
        if (patternHelper != null) {
            patternHelper.showSample(result);
        }
    }

    /** "Retry": listed again. */
    private void resample() {
        sampledIn = null;
        ensureSample();
    }

    private void cancelSample() {
        if (sampleSession != null) {
            sampleSession.cancel();
        }
    }

    private ConfigForm.Access advancedAccess(EditorNode node, JsonObject step) {
        return new ConfigForm.Access() {
            @Override
            public String text(ConfigField field) {
                return document.advancedText(step, field);
            }

            /** "version" changes which plugin the step resolves to: written on Enter or focus loss only. */
            @Override
            public boolean commitOnEdit(ConfigField field) {
                return !isVersion(field);
            }

            @Override
            public void setText(ConfigField field, String text) {
                if (!isVersion(field)) {
                    document.setAdvancedText(step, field, text);
                    return;
                }
                Optional<CatalogAction> before = catalog.find(node.section(), step);
                Optional<FailedPlugin> failedBefore = catalog.failedPlugin(step);
                document.setAdvancedText(step, field, text);
                if (!catalog.find(node.section(), step).equals(before)
                        || !catalog.failedPlugin(step).equals(failedBefore)) {
                    tree.refresh();
                    // another action, or none (read-only form): rebuilt once the focus change is over
                    Platform.runLater(() -> {
                        if (node.equals(selectedNode()) && step == step(node)) {
                            showForm(node);
                        }
                    });
                }
            }

            private boolean isVersion(ConfigField field) {
                return "version".equals(field.name());
            }

            @Override
            public String label(ConfigField field) {
                return ResourcesEngine.getString(field.labelKey());
            }

            @Override
            public String description(ConfigField field) {
                return ResourcesEngine.getString(field.descriptionKey());
            }

            @Override
            public String json(ConfigField field) {
                return PipelineDocument.json(step.get(field.name()));
            }
        };
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        return label;
    }

    private static TextArea readOnly(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setPrefRowCount(12);
        return area;
    }

    // ---- save ----

    @FXML
    protected void onSaveClick() {
        if (saving || !canSave() || !confirmIncomplete()) {
            return;
        }
        if (path == null) {
            chooseTarget().ifPresent(this::saveTo);
        } else {
            saveTo(path);
        }
    }

    @FXML
    protected void onSaveAsClick() {
        if (saving || !canSave() || !confirmIncomplete()) {
            return;
        }
        chooseTarget().ifPresent(this::saveTo);
    }

    /**
     * The warning of a pipeline without input step, or doing nothing with the files (not blocking, unlike the
     * required fields): true to save anyway. Asked on every save, before any file chooser.
     */
    private boolean confirmIncomplete() {
        List<PipelineDocument.Gap> gaps = document.gaps();
        if (gaps.isEmpty()) {
            return true;
        }
        ButtonType saveAnyway = new ButtonType(ResourcesEngine.getString("editor.save-anyway"), ButtonBar.ButtonData.OK_DONE);
        String lines = gaps.stream().map(PipelineDocument.Gap::message).collect(Collectors.joining("\n"));
        Alert alert = new Alert(Alert.AlertType.WARNING, ResourcesEngine.getString("editor.incomplete", lines),
                saveAnyway, ButtonType.CANCEL);
        alert.initOwner(stage);
        return alert.showAndWait().filter(choice -> choice == saveAnyway).isPresent();
    }

    /**
     * Saves, after a warning the first time the target is the file read as non-strict JSON (its comments
     * and layout will be lost) or with duplicate members (all but the last value will be lost). The user may
     * save anyway, save to another file or cancel.
     */
    private void saveTo(Path target) {
        if (lossyFile == null || !lossyFile.equals(target)) {
            save(target);
            return;
        }
        List<String> losses = new ArrayList<>();
        if (document.isLenient()) {
            losses.add(ResourcesEngine.getString("editor.lenient"));
        }
        if (!document.duplicateKeys().isEmpty()) {
            losses.add(ResourcesEngine.getString("editor.duplicate-keys", String.join("\n", document.duplicateKeys())));
        }
        ButtonType saveAnyway = new ButtonType(ResourcesEngine.getString("editor.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType saveAs = new ButtonType(ResourcesEngine.getString("editor.save-as"), ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n\n", losses),
                saveAnyway, saveAs, ButtonType.CANCEL);
        alert.initOwner(stage);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        if (choice.get() == saveAnyway) {
            lossyFile = null; // agreed: no warning again for this file
            save(target);
        } else {
            chooseTarget().ifPresent(this::saveTo);
        }
    }

    /**
     * The file to save to, ".json" appended to a name without extension
     * ({@link PipelineDocument#withDefaultExtension}; then confirmed if it exists: the chooser only
     * confirmed the name typed). A resume cursor (".state.json") is refused.
     */
    private Optional<Path> chooseTarget() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ResourcesEngine.getString("home.file-filter"), "*.json"));
        if (path != null) {
            chooser.setInitialDirectory(path.getParent().toFile());
            chooser.setInitialFileName(path.getFileName().toString());
        }
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return Optional.empty();
        }
        Path chosen = file.toPath();
        Path withExtension = PipelineDocument.withDefaultExtension(chosen);
        boolean appended = !withExtension.equals(chosen);
        Path target = withExtension.toAbsolutePath().normalize();
        if (PipelineDocument.isResumeCursor(target)) {
            warn(ResourcesEngine.getString("home.state-file", target.getFileName().toString()));
            return Optional.empty();
        }
        // a quick local check (the chooser has just listed this directory)
        if (appended && Files.exists(target)
                && !confirm(ResourcesEngine.getString("editor.overwrite", target.getFileName().toString()))) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * Writes the pending edit of the focused field, then refuses (with a warning) while a field holds an
     * invalid value or a required field is empty (spec desktop-ui §3). Checked before any file chooser: the
     * dialog takes the focus, which resets an invalid field.
     */
    private boolean canSave() {
        commitPending();
        if (!invalid.isEmpty()) {
            warn(ResourcesEngine.getString("editor.invalid-fields", String.join("\n", invalid.labels())));
            return false;
        }
        List<Problem> problems = document.validate(catalog);
        if (!problems.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Problem problem : problems) {
                lines.add(ResourcesEngine.getString("editor.section." + problem.section().name())
                        + " #" + (problem.index() + 1) + " : " + fieldLabel(problem));
            }
            warn(ResourcesEngine.getString("editor.required-missing", String.join("\n", lines)));
            return false;
        }
        return true;
    }

    /** The focused field may write only on Enter or focus loss ("version"): its text is written now. */
    private void commitPending() {
        Node focused = stage.getScene().getFocusOwner();
        if (focused != null && focused.getProperties().get(ConfigForm.COMMIT) instanceof Runnable commit) {
            commit.run();
        }
    }

    private void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    /**
     * Writes the file in the background, the window disabled meanwhile (the document is not touched on the
     * JavaFX thread while it is written), then closes the window; it stays open if the write fails.
     */
    private void save(Path target) {
        setSaving(true);
        background(() -> {
                    document.save(target);
                    return target;
                },
                saved -> {
                    setSaving(false);
                    stage.close(); // not a close request: nothing to discard
                    if (onSaved != null) {
                        try {
                            onSaved.accept(saved);
                        } catch (RuntimeException e) {
                            PopinUtil.showError(e);
                        }
                    }
                },
                e -> {
                    setSaving(false);
                    PopinUtil.showError(saveFailed(target, e));
                });
    }

    /** The failure of a save, with a localized message naming the file (the cause in the stack trace). */
    private static Exception saveFailed(Path target, Exception cause) {
        String message = ResourcesEngine.getString("editor.save-failed", target.toString());
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message += "\n" + cause.getMessage();
        }
        return new IOException(message, cause);
    }

    private void setSaving(boolean value) {
        saving = value;
        stage.getScene().getRoot().setDisable(value);
        stage.getScene().setCursor(value ? Cursor.WAIT : null);
    }

    private String fieldLabel(Problem problem) {
        JsonObject step = document.steps(problem.section()).get(problem.index());
        return catalog.find(problem.section(), step)
                .flatMap(action -> action.configSchema()
                        .flatMap(schema -> schema.field(problem.fieldPath()))
                        .map(action::label))
                .orElse(problem.fieldPath());
    }

    @FXML
    protected void onShowJsonClick() {
        if (saving) { // Ctrl+J while the document is being written
            return;
        }
        commitPending();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(ResourcesEngine.getString("editor.json.title"));
        alert.setHeaderText(null);
        TextArea json = readOnly(document.toJson());
        json.setPrefSize(640, 480);
        alert.getDialogPane().setContent(json);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private boolean confirm(String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
