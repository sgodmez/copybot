package com.copybot.ui;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
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
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

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
     * The file read as non-strict JSON (comments...), until the user agreed to rewrite it as strict JSON
     * (null then, or when the file was strict): the first save over it warns first.
     */
    private Path lenientFile;
    private StepCatalog catalog;
    private Consumer<Path> onSaved;
    /** A save runs in the background: the window is disabled and cannot be closed meanwhile. */
    private boolean saving;
    /** The controls of the current form whose text does not parse: no save meanwhile. */
    private final ConfigForm.Invalid invalid = new ConfigForm.Invalid();

    /** A pipeline file is being read for {@link #open} (JavaFX thread only): another open is ignored. */
    private static boolean loading;

    /**
     * Opens the editor, modal, on this pipeline file or on a new pipeline (path null). Call it on the
     * JavaFX thread; the file is read in the background, the window opens once it is read (an error popup
     * otherwise), so this method returns at once. While a file is being read, another call is ignored (a
     * double click on the trigger opens one editor).
     *
     * @param onSaved called with the file after each save (on the JavaFX thread)
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
        // the catalog (plugin instances, bundles) is built off the JavaFX thread too
        background(() -> new Loaded(file == null ? PipelineDocument.empty() : PipelineDocument.load(file),
                        new StepCatalog(PluginEngine.catalog())),
                loaded -> {
                    loading = false;
                    resetCursor(ownerScene);
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
        this.lenientFile = document.isLenient() ? this.path : null;
        this.onSaved = onSaved;
        this.catalog = catalog;
        stage.setOnCloseRequest(e -> {
            if (saving || (document.isModified() && !confirm(ResourcesEngine.getString("editor.discard")))) {
                e.consume();
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

    /** startProcessingWhileListing, resume.mode, ui.autoExecute (spec desktop-ui §3). */
    private void pipelineForm() {
        CheckBox startProcessing = new CheckBox(ResourcesEngine.getString("editor.startProcessingWhileListing"));
        startProcessing.setSelected(document.startProcessingWhileListing());
        startProcessing.setOnAction(e -> document.setStartProcessingWhileListing(startProcessing.isSelected()));

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
        resumeMode.valueProperty().addListener((obs, old, mode) ->
                document.setResumeMode(mode == null || mode.isEmpty() ? null : mode));

        CheckBox autoExecute = new CheckBox(ResourcesEngine.getString("editor.autoExecute"));
        autoExecute.setSelected(document.autoExecute());
        autoExecute.setOnAction(e -> document.setAutoExecute(autoExecute.isSelected()));

        formBox.getChildren().addAll(title(ResourcesEngine.getString("editor.section.PIPELINE")), startProcessing,
                new VBox(3, new Label(ResourcesEngine.getString("editor.resume-mode")), resumeMode), autoExecute);
    }

    /** The generated form of the action's schema, then "Advanced"; read-only when the plugin is missing. */
    private void stepForm(EditorNode node) {
        JsonObject step = step(node);
        Optional<CatalogAction> action = catalog.find(node.section(), step);
        if (action.isEmpty()) {
            String plugin = member(step, "plugin");
            formBox.getChildren().addAll(title(member(step, "action")),
                    new Label(ResourcesEngine.getString("editor.plugin-not-found",
                            plugin.isEmpty() ? CatalogAction.EMBEDDED_PLUGIN : plugin)),
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
            formBox.getChildren().add(ConfigForm.build(schema.get().fields(), configAccess(step, catalogAction), stage,
                    invalid));
        } else {
            formBox.getChildren().addAll(new Label(ResourcesEngine.getString("editor.no-schema")),
                    readOnly(PipelineDocument.json(step.get("actionConfig"))));
        }
        TitledPane advanced = new TitledPane(ResourcesEngine.getString("editor.advanced"),
                ConfigForm.build(PipelineDocument.ADVANCED_FIELDS, advancedAccess(node, step), stage, invalid));
        advanced.setExpanded(false);
        formBox.getChildren().add(advanced);
    }

    private ConfigForm.Access configAccess(JsonObject step, CatalogAction action) {
        return new ConfigForm.Access() {
            @Override
            public String text(ConfigField field) {
                return document.configText(step, field);
            }

            @Override
            public void setText(ConfigField field, String text) {
                document.setConfigText(step, field, text);
            }

            @Override
            public String label(ConfigField field) {
                return action.label(field);
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
                document.setAdvancedText(step, field, text);
                if (!catalog.find(node.section(), step).equals(before)) {
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
        if (saving || !canSave()) {
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
        if (saving || !canSave()) {
            return;
        }
        chooseTarget().ifPresent(this::saveTo);
    }

    /**
     * Saves, after a warning the first time the target is the file read as non-strict JSON: its comments
     * and layout will be lost. The user may save anyway, save to another file or cancel.
     */
    private void saveTo(Path target) {
        if (lenientFile == null || !lenientFile.equals(target)) {
            save(target);
            return;
        }
        ButtonType saveAnyway = new ButtonType(ResourcesEngine.getString("editor.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType saveAs = new ButtonType(ResourcesEngine.getString("editor.save-as"), ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.WARNING, ResourcesEngine.getString("editor.lenient"),
                saveAnyway, saveAs, ButtonType.CANCEL);
        alert.initOwner(stage);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        if (choice.get() == saveAnyway) {
            lenientFile = null; // agreed: no warning again for this file
            save(target);
        } else {
            chooseTarget().ifPresent(this::saveTo);
        }
    }

    /**
     * The file to save to, ".json" appended when missing (then confirmed if it exists: the chooser only
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
        boolean appended = false;
        if (!chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            chosen = chosen.resolveSibling(chosen.getFileName() + ".json");
            appended = true;
        }
        Path target = chosen.toAbsolutePath().normalize();
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
     * JavaFX thread while it is written).
     */
    private void save(Path target) {
        setSaving(true);
        background(() -> {
                    document.save(target);
                    return target;
                },
                saved -> {
                    setSaving(false);
                    path = saved;
                    updateTitle();
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
