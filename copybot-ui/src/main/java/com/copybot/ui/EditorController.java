package com.copybot.ui;

import com.copybot.engine.pipeline.ConflictCheck;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.SecondaryOutputConfig;
import com.copybot.engine.plugin.CatalogAction;
import com.copybot.engine.plugin.PluginCatalog.FailedPlugin;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineDocument.Problem;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.model.PipelineDocument.StepAddress;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.plugins.PluginRow;
import com.copybot.ui.plugins.PluginService;
import com.copybot.ui.plugins.PluginState;
import com.copybot.ui.util.Background;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The generic pipeline editor (spec desktop-ui §3, variant A): a tree of the sections on the left, the form
 * of the selected element on the right. It edits a {@link PipelineDocument}; only the fields it shows are
 * rewritten. The pipeline file is read and written off the JavaFX thread (it may be on a network share).
 */
public class EditorController {

    private static final System.Logger LOG = System.getLogger(EditorController.class.getName());

    enum Kind { PIPELINE, SECTION, STEP, GROUPING, ARCHIVES, SECONDARY, BRANCH }

    /**
     * A node of the tree: the pipeline, a section, the step at index of a section, the grouping, the archives, the
     * secondary outputs, one of them (branch). A section or a step is in the main pipeline ({@link StepAddress#MAIN})
     * or in the secondary output at index {@code branch} (spec secondary-outputs §8).
     */
    record EditorNode(Kind kind, int branch, Section section, int index) {

        static EditorNode section(int branch, Section section) {
            return new EditorNode(Kind.SECTION, branch, section, -1);
        }

        static EditorNode step(StepAddress address) {
            return new EditorNode(Kind.STEP, address.branch(), address.section(), address.index());
        }

        static EditorNode branch(int branch) {
            return new EditorNode(Kind.BRANCH, branch, null, -1);
        }

        /** The address of the step node. */
        StepAddress address() {
            return new StepAddress(branch, section, index);
        }
    }

    private static final EditorNode PIPELINE_NODE = new EditorNode(Kind.PIPELINE, StepAddress.MAIN, null, -1);
    private static final EditorNode GROUPING_NODE = new EditorNode(Kind.GROUPING, StepAddress.MAIN, null, -1);
    private static final EditorNode ARCHIVES_NODE = new EditorNode(Kind.ARCHIVES, StepAddress.MAIN, null, -1);
    private static final EditorNode SECONDARY_NODE = new EditorNode(Kind.SECONDARY, StepAddress.MAIN, null, -1);
    private static final String WARNING_STYLE = "-fx-text-fill: #c87f0a;";
    private static final String BLOCKING_STYLE = "-fx-text-fill: #c62828;";

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
    /** The rows of the official plugins the pipeline names that are not loaded, by module (empty until read). */
    private volatile Map<String, PluginRow> officialRows = Map.of();
    private Consumer<Path> onSaved;
    /** A save runs in the background: the window is disabled and cannot be closed meanwhile. */
    private boolean saving;
    /** The controls of the current form whose text does not parse: no save meanwhile. */
    private final ConfigForm.Invalid invalid = new ConfigForm.Invalid();
    /** Shows again the problems of the secondary output of the current form after an edit; nothing otherwise. */
    private Runnable refreshBranchProblems = () -> {
    };

    /** The sample and the tested files of the out form's helper (spec pattern-helper §5). */
    private EditorSamples samples;

    /** A pipeline file is being read for {@link #open} (JavaFX thread only): another open is ignored. */
    private static boolean loading;
    /** The editors shown (JavaFX thread only), for the exit of the application. */
    private static final Set<EditorController> OPEN = new LinkedHashSet<>();

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
        Background.run(() -> new Loaded(file == null ? PipelineDocument.empty() : PipelineDocument.load(file),
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
     * On exit, every open editor is closed: one with unsaved changes asks first, as when its window is closed.
     * False when one stays open (the user keeps its changes, or a save is running); the exit is then given up.
     */
    static boolean closeAllForExit() {
        if (!confirmAllForExit()) {
            return false;
        }
        closeAll();
        return true;
    }

    /**
     * The questions of {@link #closeAllForExit()} without closing anything (an update asks before its download, and
     * closes the editors only once it is launched). False when one is to stay open.
     */
    static boolean confirmAllForExit() {
        for (EditorController editor : List.copyOf(OPEN)) {
            if (editor.saving || editor.document.isModified()
                    && !editor.confirm(ResourcesEngine.getString("editor.discard"))) {
                return false;
            }
        }
        return true;
    }

    /** Closes every open editor, its changes given up: the user was asked by {@link #confirmAllForExit()}. */
    static void closeAll() {
        for (EditorController editor : List.copyOf(OPEN)) {
            editor.stage.close(); // not a close request: it was asked before
        }
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
        this.samples = new EditorSamples(document);
        stage.setOnCloseRequest(e -> {
            if (saving || (document.isModified() && !confirm(ResourcesEngine.getString("editor.discard")))) {
                e.consume();
            }
        });
        // both ways out (an accepted close request, the close after a save): the sample is no longer wanted
        OPEN.add(this);
        stage.setOnHidden(e -> {
            OPEN.remove(this);
            samples.close();
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
        readCatalogueForMissingPlugins();
    }

    /**
     * When the pipeline names plugins that are not loaded, their rows of the catalogue of the official plugins are
     * built in the background (the catalogue in memory, else read; the plugins folder checked): the form of such a
     * step then says whether the plugin is not installed or installed but not loaded, with "Install" only when the
     * Plugins window offers it (spec plugin-install §6). A failure (offline) leaves the "not found" message. The
     * plugins of the grouping presets count too: the grouping form then offers "Install" the same way.
     */
    private void readCatalogueForMissingPlugins() {
        Optional<PluginService> service = UpdateUi.plugins();
        List<String> missing = catalog.missingPlugins(document);
        if (service.isEmpty() || missing.isEmpty()) {
            return;
        }
        Background.run(() -> service.get().rowsOf(missing, service.get().catalog()),
                rows -> {
                    officialRows = rows.stream().collect(Collectors.toMap(row -> row.entry().module(), row -> row,
                            (a, b) -> a));
                    EditorNode node = selectedNode();
                    if (stage.isShowing() && node != null && (node.kind() == Kind.GROUPING
                            || node.kind() == Kind.STEP && catalog.find(node.section(), step(node)).isEmpty())) {
                        showForm(node); // the read-only form of a missing plugin: now with what can be done
                    }
                },
                failure -> LOG.log(System.Logger.Level.INFO, "plugin catalogue not read: {0}",
                        UpdateUi.message(failure)));
    }

    /** The row of the official plugin of this name, not loaded; empty before the catalogue is read. */
    private Optional<PluginRow> officialRow(String plugin) {
        return catalog.isLoaded(plugin) ? Optional.empty() : Optional.ofNullable(officialRows.get(plugin));
    }

    /**
     * "Install" (or "Update") of a plugin that is not loaded, when the Plugins window offers it: that window with this
     * plugin chosen (its install, or the update that fixes it), over the editor (modal).
     */
    private Optional<Node> installButton(PluginRow row) {
        if (!row.offersInstall()) {
            return Optional.empty();
        }
        Button install = new Button(ResourcesEngine.getString(row.state() == PluginState.UPDATE_AVAILABLE
                ? "plugins.action.UPDATE" : "editor.plugin-install"));
        install.setOnAction(e -> CopybotMainUi.mainController()
                .ifPresent(main -> main.openPlugins(Set.of(row.entry().module()), false, stage)));
        return Optional.of(install);
    }

    /**
     * Why a step of a plugin that is not loaded cannot be shown: "not installed" only when it is not; installed but
     * not loaded (refused for its API, a missing dependency...) points to the Plugins window; otherwise "not found".
     */
    private static String missingPluginKey(Optional<PluginRow> row) {
        return row.map(r -> switch (r.state()) {
            case NOT_INSTALLED -> "editor.plugin-not-installed";
            case INCOMPATIBLE -> "editor.plugin-not-found";
            default -> "editor.plugin-not-loaded";
        }).orElse("editor.plugin-not-found");
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
        TreeItem<EditorNode> pipeline = new TreeItem<>(PIPELINE_NODE);
        root.getChildren().add(pipeline);
        for (Section section : Section.values()) {
            root.getChildren().add(sectionItem(StepAddress.MAIN, section));
            if (section == Section.IN) { // grouping and archives work on the listing, right after the inputs
                root.getChildren().add(new TreeItem<>(GROUPING_NODE));
                root.getChildren().add(new TreeItem<>(ARCHIVES_NODE));
            }
            if (section == Section.OUT) { // the secondary outputs: each with its process steps then its out step
                TreeItem<EditorNode> secondary = new TreeItem<>(SECONDARY_NODE);
                secondary.setExpanded(true);
                for (int branch = 0; branch < document.branchCount(); branch++) {
                    TreeItem<EditorNode> branchItem = new TreeItem<>(EditorNode.branch(branch));
                    branchItem.setExpanded(true);
                    for (Section own : PipelineDocument.BRANCH_SECTIONS) {
                        branchItem.getChildren().add(sectionItem(branch, own));
                    }
                    secondary.getChildren().add(branchItem);
                }
                root.getChildren().add(secondary);
            }
        }
        tree.setRoot(root);
        tree.getSelectionModel().select(Optional.ofNullable(find(root, select)).orElse(pipeline));
    }

    /** The section of the branch, expanded, with its steps. */
    private TreeItem<EditorNode> sectionItem(int branch, Section section) {
        TreeItem<EditorNode> sectionItem = new TreeItem<>(EditorNode.section(branch, section));
        sectionItem.setExpanded(true);
        for (int i = 0; i < document.steps(branch, section).size(); i++) {
            sectionItem.getChildren().add(new TreeItem<>(EditorNode.step(new StepAddress(branch, section, i))));
        }
        return sectionItem;
    }

    /** The item of this node under item, null when there is none. */
    private static TreeItem<EditorNode> find(TreeItem<EditorNode> item, EditorNode node) {
        if (node == null) {
            return null;
        }
        if (node.equals(item.getValue())) {
            return item;
        }
        for (TreeItem<EditorNode> child : item.getChildren()) {
            TreeItem<EditorNode> found = find(child, node);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    String label(EditorNode node) {
        return switch (node.kind()) {
            case PIPELINE -> ResourcesEngine.getString("editor.section.PIPELINE");
            case GROUPING -> ResourcesEngine.getString("editor.grouping");
            case ARCHIVES -> ResourcesEngine.getString("editor.archives");
            case SECONDARY -> ResourcesEngine.getString("editor.secondary");
            case BRANCH -> branchLabel(node.branch());
            case SECTION -> ResourcesEngine.getString("editor.section." + node.section().name());
            case STEP -> {
                JsonObject step = step(node);
                yield catalog.find(node.section(), step).map(CatalogAction::name)
                        .orElseGet(() -> member(step, "action") + " (?)");
            }
        };
    }

    /** The name of the secondary output, "#2" without one. */
    private String branchLabel(int branch) {
        String name = document.branchName(branch);
        return name.isEmpty() ? "#" + (branch + 1) : name;
    }

    /** The name of the secondary output between the quotes of the language, "#2" without one. */
    String quotedBranch(int branch) {
        String name = document.branchName(branch);
        return name.isEmpty() ? "#" + (branch + 1) : ResourcesEngine.getString("editor.secondary.quoted", name);
    }

    private JsonObject step(EditorNode node) {
        return document.step(node.address());
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
        Kind kind = node == null ? null : node.kind();
        boolean step = kind == Kind.STEP;
        boolean branch = kind == Kind.BRANCH;
        addButton.setDisable(kind == Kind.SECONDARY ? !document.canAddBranch()
                : node == null || node.section() == null || !document.canAdd(node.branch(), node.section()));
        upButton.setDisable(branch ? !document.canMoveBranch(node.branch(), -1)
                : !step || !document.canMove(node.address(), -1));
        downButton.setDisable(branch ? !document.canMoveBranch(node.branch(), 1)
                : !step || !document.canMove(node.address(), 1));
        removeButton.setDisable(!step && !branch);
    }

    @FXML
    protected void onAddClick() {
        EditorNode node = selectedNode();
        if (node.kind() == Kind.SECONDARY) { // named at once, renamed in its form
            rebuildTree(EditorNode.branch(document.addBranch(newBranchName())));
            return;
        }
        int branch = node.branch();
        Section section = node.section();
        chooseAction(section).ifPresent(action -> {
            document.addStep(branch, section, action);
            rebuildTree(EditorNode.step(new StepAddress(branch, section, document.steps(branch, section).size() - 1)));
        });
    }

    /** "Secondary output 2": the first number from the count + 1 whose name is not taken (case ignored, as the engine). */
    private String newBranchName() {
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < document.branchCount(); i++) {
            taken.add(document.branchName(i).toLowerCase(Locale.ROOT));
        }
        for (int n = document.branchCount() + 1; ; n++) {
            String name = ResourcesEngine.getString("editor.secondary.new-name", String.valueOf(n));
            if (!taken.contains(name.strip().toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
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
        if (node.kind() == Kind.BRANCH) {
            document.moveBranch(node.branch(), delta);
            rebuildTree(EditorNode.branch(node.branch() + delta));
            return;
        }
        document.moveStep(node.address(), delta);
        rebuildTree(EditorNode.step(new StepAddress(node.branch(), node.section(), node.index() + delta)));
    }

    @FXML
    protected void onRemoveClick() {
        EditorNode node = selectedNode();
        if (node.kind() == Kind.BRANCH) {
            int branch = node.branch();
            boolean hasSteps = PipelineDocument.BRANCH_SECTIONS.stream()
                    .anyMatch(section -> !document.steps(branch, section).isEmpty());
            // its steps go with it: asked first when there are some
            if (hasSteps && !confirm(ResourcesEngine.getString("editor.secondary.remove-confirm", quotedBranch(branch)))) {
                return;
            }
            document.removeBranch(branch);
            rebuildTree(SECONDARY_NODE);
            return;
        }
        document.removeStep(node.address());
        rebuildTree(EditorNode.section(node.branch(), node.section()));
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
        samples.formReplaced(); // its helper belongs to the form being replaced
        formBox.getChildren().clear();
        invalid.clear();
        refreshBranchProblems = () -> {
        };
        if (node == null || node.kind() == Kind.SECTION) {
            return;
        }
        if (node.kind() == Kind.PIPELINE) {
            pipelineForm();
        } else if (node.kind() == Kind.GROUPING) {
            formBox.getChildren().add(GroupingForms.groupingForm(document, catalog,
                    plugin -> officialRow(plugin).flatMap(this::installButton), stage, () -> showForm(node)));
        } else if (node.kind() == Kind.ARCHIVES) {
            formBox.getChildren().add(GroupingForms.archivesForm(document));
        } else if (node.kind() == Kind.SECONDARY) {
            secondaryForm();
        } else if (node.kind() == Kind.BRANCH) {
            branchForm(node.branch());
        } else {
            stepForm(node);
        }
    }

    /** What the secondary outputs are for, and every problem of theirs (spec secondary-outputs §8). */
    private void secondaryForm() {
        Label help = new Label(ResourcesEngine.getString("editor.secondary.help"));
        help.setWrapText(true);
        formBox.getChildren().addAll(title(ResourcesEngine.getString("editor.secondary")), help);
        if (document.branchCount() == 0) {
            Label empty = new Label(ResourcesEngine.getString("editor.secondary.empty"));
            empty.setWrapText(true);
            formBox.getChildren().add(empty);
        }
        formBox.getChildren().add(branchProblems(null));
    }

    /** The name and what an error does, then the problems of the secondary output (spec secondary-outputs §8). */
    private void branchForm(int branch) {
        Label title = title(branchLabel(branch));
        VBox problems = branchProblems(branch);
        TextField name = new TextField(document.branchName(branch));
        name.textProperty().addListener((obs, old, text) -> {
            document.setBranchName(branch, text);
            title.setText(branchLabel(branch));
            tree.refresh();
            refreshBranchProblems.run();
        });
        ComboBox<SecondaryOutputConfig.OnError> onError = new ComboBox<>();
        onError.getItems().addAll(SecondaryOutputConfig.OnError.values());
        onError.setConverter(new StringConverter<>() {
            @Override
            public String toString(SecondaryOutputConfig.OnError value) {
                return value == null ? "" : ResourcesEngine.getString("editor.secondary.on-error." + value.name());
            }

            @Override
            public SecondaryOutputConfig.OnError fromString(String s) {
                return null; // not editable
            }
        });
        // an unknown stored value (a problem shown above) selects nothing, so that choosing fail still writes it
        boolean unknown = document.branchProblems().stream().anyMatch(problem -> problem.branch() == branch
                && problem.kind() == PipelineDocument.BranchProblem.Kind.ON_ERROR_UNKNOWN);
        onError.setValue(unknown ? null : document.onError(branch));
        onError.valueProperty().addListener((obs, old, value) -> {
            document.setOnError(branch, value == null ? SecondaryOutputConfig.OnError.FAIL : value);
            refreshBranchProblems.run();
        });
        formBox.getChildren().addAll(title, problems,
                new VBox(3, new Label(ResourcesEngine.getString("editor.secondary.name")), name),
                new VBox(3, new Label(ResourcesEngine.getString("editor.secondary.on-error")), onError));
    }

    /**
     * The problems of the secondary output (all of them for null), kept up to date by {@link #refreshBranchProblems}:
     * what the engine would refuse in red, a source that will be kept anyway as a warning.
     */
    private VBox branchProblems(Integer branch) {
        VBox box = new VBox(3);
        refreshBranchProblems = () -> {
            box.getChildren().clear();
            for (PipelineDocument.BranchProblem problem : document.branchProblems()) {
                if (branch == null || problem.branch() == branch) {
                    Label label = new Label("⚠ " + problem.message());
                    label.setWrapText(true);
                    label.setStyle(problem.kind().blocking() ? BLOCKING_STYLE : WARNING_STYLE);
                    box.getChildren().add(label);
                }
            }
            box.setManaged(!box.getChildren().isEmpty());
        };
        refreshBranchProblems.run();
        return box;
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
        Label executionWarning = new Label("⚠ " + ResourcesEngine.getString("editor.execution.streaming-last-found"));
        executionWarning.setWrapText(true);
        executionWarning.setStyle("-fx-text-fill: #c87f0a;");
        Label groupingWarning = new Label(); // streaming with grouping rules: folder by folder, or automatic mode

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
            GroupingForms.showStreamingWarning(groupingWarning, document);
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
                new VBox(3, new Label(ResourcesEngine.getString("editor.execution")), execution, executionWarning, groupingWarning),
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
            Optional<PluginRow> official = catalog.failedPlugin(step).isPresent() || plugin.isBlank()
                    ? Optional.empty() : officialRow(plugin);
            String message = catalog.failedPlugin(step)
                    .map(failed -> ResourcesEngine.getString("editor.plugin-failed", failed.pluginVersion() == null
                            ? failed.pluginName() : failed.pluginName() + " " + failed.pluginVersion()))
                    .orElseGet(() -> ResourcesEngine.getString(missingPluginKey(official),
                            plugin.isEmpty() ? CatalogAction.EMBEDDED_PLUGIN : plugin));
            Label messageLabel = new Label(message);
            messageLabel.setWrapText(true);
            formBox.getChildren().addAll(title(member(step, "action")), messageLabel);
            official.flatMap(this::installButton).ifPresent(formBox.getChildren()::add);
            if (node.branch() != StepAddress.MAIN) {
                formBox.getChildren().add(branchProblems(node.branch()));
            }
            formBox.getChildren().add(readOnly(PipelineDocument.json(step)));
            return;
        }
        CatalogAction catalogAction = action.get();
        formBox.getChildren().add(title(catalogAction.name()));
        if (!catalogAction.description().isEmpty()) {
            Label description = new Label(catalogAction.description());
            description.setWrapText(true);
            formBox.getChildren().add(description);
        }
        if (node.branch() != StepAddress.MAIN) { // e.g. its file.write deleting the source: kept anyway
            formBox.getChildren().add(branchProblems(node.branch()));
        }
        Optional<ConfigSchema> schema = catalogAction.configSchema();
        if (schema.isPresent()) {
            // the helper's sample goes through the main process steps: not what a secondary output gets
            boolean mainOut = node.section() == Section.OUT && node.branch() == StepAddress.MAIN;
            formBox.getChildren().add(ConfigForm.build(schema.get().fields(),
                    configAccess(step, catalogAction, mainOut), stage, invalid));
        } else {
            formBox.getChildren().addAll(new Label(ResourcesEngine.getString("editor.no-schema")),
                    readOnly(PipelineDocument.json(step.get("actionConfig"))));
        }
        if (node.section() == Section.RUN_END) {
            formBox.getChildren().add(RunEndForms.conditions(document, step));
        }
        TitledPane advanced = new TitledPane(ResourcesEngine.getString("editor.advanced"), ConfigForm.build(
                PipelineDocument.advancedFields(node.section()), advancedAccess(node, step), stage, invalid));
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
                samples.refreshHelper(); // "onMissingKey" may have changed: the effects shown follow it
                refreshBranchProblems.run(); // "deleteSource" of the out step of a secondary output
            }

            @Override
            public ConfigForm.Helper patternHelper(ConfigField field, TextInputControl input) {
                if (!outStep) {
                    return null;
                }
                ConfigField onMissingKey = action.configSchema().flatMap(schema -> schema.field("onMissingKey")).orElse(null);
                PatternHelper helper = samples.helperFor(input,
                        () -> onMissingKey == null ? null : document.configText(step, onMissingKey));
                return new ConfigForm.Helper(helper.pickerButton(), helper);
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
        if (saving || !canSave() || !confirmIncomplete() || !confirmUnknownSecrets()) {
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
        if (saving || !canSave() || !confirmIncomplete() || !confirmUnknownSecrets()) {
            return;
        }
        chooseTarget().ifPresent(this::saveTo);
    }

    /**
     * Like {@link #confirmIncomplete()} for the secrets the pipeline refers to that are not defined on this machine
     * (not blocking: it may be meant for another machine). Asked on every save, before any file chooser.
     */
    private boolean confirmUnknownSecrets() {
        List<PipelineDocument.UnknownSecret> unknown = document.unknownSecrets(SecretsDialog.names());
        if (unknown.isEmpty()) {
            return true;
        }
        String lines = unknown.stream().map(secret -> stepLabel(secret.address()) + " : " + secret.name())
                .collect(Collectors.joining("\n"));
        ButtonType saveAnyway = new ButtonType(ResourcesEngine.getString("editor.save-anyway"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING, ResourcesEngine.getString("editor.unknown-secrets", lines),
                saveAnyway, ButtonType.CANCEL);
        alert.initOwner(stage);
        return alert.showAndWait().filter(choice -> choice == saveAnyway).isPresent();
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
                lines.add(stepLabel(problem.address()) + " : " + fieldLabel(problem));
            }
            warn(ResourcesEngine.getString("editor.required-missing", String.join("\n", lines)));
            return false;
        }
        // what the engine would refuse in the secondary outputs (spec secondary-outputs §7): its own sentences
        List<String> refused = document.branchProblems().stream().filter(problem -> problem.kind().blocking())
                .map(PipelineDocument.BranchProblem::message).toList();
        if (!refused.isEmpty()) {
            warn(String.join("\n", refused));
            return false;
        }
        return true;
    }

    /** "Processing #2", prefixed with the name of its secondary output for a step of one. */
    String stepLabel(StepAddress address) {
        String label = ResourcesEngine.getString("editor.section." + address.section().name())
                + " #" + (address.index() + 1);
        if (!address.inBranch()) {
            return label;
        }
        return quotedBranch(address.branch()) + " / " + label;
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
        Background.run(() -> {
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
    private static Exception saveFailed(Path target, Throwable cause) {
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
        if (problem.section() == Section.RUN_END && "when".equals(problem.fieldPath())) {
            return ResourcesEngine.getString("editor.run-end.when");
        }
        JsonObject step = document.step(problem.address());
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
