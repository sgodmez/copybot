package com.copybot.ui;

import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineSummary;
import com.copybot.ui.model.PlanOperations;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The plan view of one pipeline (spec desktop-ui §2), a thin layer over {@link PlanViewModel}: the engine
 * operations are coordinated by {@link PlanOperations} (background threads, operation tokens, the engine held),
 * this class renders the model and handles the rows, the header ({@link PlanHeader}), the resources
 * ({@link PlanGauges}) and the dialogs.
 */
public class PlanController implements PlanOperations.View {

    @FXML private Button backButton;
    @FXML private Label pipelineName;
    @FXML private Button editButton;
    @FXML private Label sourceLabel;
    @FXML private Label destinationLabel;
    @FXML private Hyperlink detailsLink;
    @FXML private HBox lastRunBox;
    @FXML private VBox detailsBox;
    @FXML private FlowPane stepsFlow;
    @FXML private Label resumeModeLabel;
    @FXML private Label warningBanner;
    @FXML private VBox resumeBox;
    @FXML private Label resumeLabel;
    @FXML private Label resumeCountLabel;
    /** The files to copy whose target already exists (spec conflict-check §5). */
    @FXML private Label conflictLabel;
    @FXML private Hyperlink changeResumeLink;
    @FXML private ComboBox<Filter> filterCombo;
    @FXML private TableView<WorkItemExecution> itemsTable;
    @FXML private TableColumn<WorkItemExecution, String> nameColumn;
    @FXML private TableColumn<WorkItemExecution, WorkItemExecution> dateColumn;
    @FXML private TableColumn<WorkItemExecution, String> targetColumn;
    @FXML private TableColumn<WorkItemExecution, WorkItemExecution> sizeColumn;
    @FXML private TableColumn<WorkItemExecution, String> statusColumn;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private HBox inlineGauges;
    @FXML private VBox resourcesPanel;
    @FXML private Button prepareButton;
    @FXML private Button copyButton;
    @FXML private CheckBox autoExecuteBox;
    /** Why Prepare or Copy stays disabled (no input step, nothing done with the files, nothing to copy). */
    @FXML private Label warningLabel;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button stopButton;
    @FXML private Label statusLine;

    private final ObservableList<WorkItemExecution> rows = FXCollections.observableArrayList();
    private final Label placeholder = new Label();

    private MainController main;
    private Path pipelinePath;
    private PlanOperations<Plan, Execution> ops;
    private PlanHeader header;
    private PlanGauges gauges;
    private PipelineSummary summary;
    /** The pipeline file is being read (background): no preparation meanwhile. */
    private boolean loading;
    private Object loadToken;
    /** "Always ignore" reads and writes the pipeline file (background): no preparation nor edit meanwhile. */
    private boolean excluding;

    @FXML
    public void initialize() {
        // a header click sorts the view, not the rows: refresh() keeps comparing them with the model order
        SortedList<WorkItemExecution> sorted = new SortedList<>(rows);
        sorted.comparatorProperty().bind(itemsTable.comparatorProperty());
        itemsTable.setItems(sorted);
        itemsTable.setPlaceholder(placeholder);
        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getWorkItem().getNameDisplay()));
        nameColumn.setCellFactory(column -> new NameCell());
        dateColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        dateColumn.setCellFactory(column -> textCell(PlanViewModel::dateText));
        dateColumn.setComparator(PlanViewModel.DATE_ORDER);
        targetColumn.setCellValueFactory(c -> new SimpleStringProperty(targetText(c.getValue())));
        targetColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                WorkItemExecution item = empty || getTableRow() == null ? null : getTableRow().getItem();
                Plan shown = ops.targetsPlan();
                String tooltip = item == null || shown == null || !PlanViewModel.showsTarget(item)
                        ? null : PlanViewModel.targetTooltip(shown.projectionOf(item));
                setTooltip(tooltip == null ? null : new Tooltip(tooltip));
            }
        });
        sizeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        sizeColumn.setCellFactory(column -> textCell(PlanViewModel::sizeText));
        sizeColumn.setComparator(PlanViewModel.SIZE_ORDER);
        statusColumn.setCellValueFactory(c -> new SimpleStringProperty(PlanViewModel.statusText(c.getValue())));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                WorkItemExecution item = empty || getTableRow() == null ? null : getTableRow().getItem();
                Optional<String> reason = item == null ? Optional.empty() : PlanViewModel.statusTooltip(item);
                setTooltip(reason.map(Tooltip::new).orElse(null));
            }
        });
        itemsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        itemsTable.setRowFactory(table -> rowWithMenu());

        filterCombo.getItems().setAll(Filter.values());
        filterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Filter filter) {
                return filter == null ? "" : ResourcesEngine.getString("plan.filter." + filter.name());
            }

            @Override
            public Filter fromString(String s) {
                return null; // not editable
            }
        });
        filterCombo.setValue(Filter.ALL);
        filterCombo.valueProperty().addListener((obs, old, filter) -> {
            model().setFilter(filter == null ? Filter.ALL : filter);
            refresh();
        });
        header = new PlanHeader(sourceLabel, destinationLabel, detailsLink, lastRunBox, detailsBox, stepsFlow,
                resumeModeLabel);
    }

    void init(MainController main, Path pipelinePath) {
        this.main = main;
        this.pipelinePath = pipelinePath;
        ops = new PlanOperations<>(new PlanEngine(pipelinePath), Background.executor(), Platform::runLater, this);
        gauges = new PlanGauges(inlineGauges, resourcesPanel, itemsTable, ops::liveState);
        pipelineName.setText(RecentPipelines.displayName(pipelinePath));
        reload();
    }

    private PlanViewModel model() {
        return ops.model();
    }

    /**
     * (Re)reads the pipeline in the background: the prepared plan, if any, is dropped (spec desktop-ui §3).
     * Only called while the engine is idle (Edit is disabled while busy).
     */
    private void reload() {
        ops.dropPlan(filterCombo.getValue() == null ? Filter.ALL : filterCombo.getValue());
        autoExecuteBox.setSelected(false);
        summary = null;
        loading = true;
        Object token = new Object();
        loadToken = token;
        renderSummary();
        refresh();
        Background.run(() -> {
                    PipelineDocument document = PipelineDocument.load(pipelinePath);
                    // the catalog (plugin instances, bundles) is built here too, off the JavaFX thread
                    try {
                        return new Loaded(document, PipelineSummary.of(document, new StepCatalog(PluginEngine.catalog())), null);
                    } catch (Throwable t) {
                        return new Loaded(document, null, t);
                    }
                },
                loaded -> onLoaded(token, loaded.document(), loaded.summary(), loaded.summaryFailure()),
                failure -> { // an Error too: the view must not stay "loading"
                    if (token == loadToken) {
                        loading = false;
                        refresh();
                        PopinUtil.showError(failure); // the preparation will report it too
                    }
                });
    }

    /** The pipeline read in the background, and its summary (null with the failure that prevented it). */
    private record Loaded(PipelineDocument document, PipelineSummary summary, Throwable summaryFailure) {
    }

    private void onLoaded(Object token, PipelineDocument document, PipelineSummary loaded, Throwable failure) {
        if (token != loadToken) {
            return;
        }
        loading = false;
        PlanViewModel model = model();
        model.setPreparationRefusal(document.gaps().contains(PipelineDocument.Gap.NO_INPUT)
                ? Optional.of(PipelineDocument.Gap.NO_INPUT.message()) : Optional.empty());
        model.setNothingDoneRefusal(document.gaps().contains(PipelineDocument.Gap.DOES_NOTHING)
                ? Optional.of(PipelineDocument.Gap.DOES_NOTHING.message()) : Optional.empty());
        model.setExecutionMode(document.executionMode()); // the box stays a one-shot toggle, unchecked
        autoExecuteBox.setSelected(model.isAutoExecute());
        summary = loaded;
        renderSummary();
        refresh();
        if (failure != null) {
            PopinUtil.showError(failure);
        }
    }

    private void renderSummary() {
        header.render(summary, pipelinePath);
    }

    @FXML
    protected void onDetailsClick() {
        header.toggleDetails();
    }

    private String targetText(WorkItemExecution item) {
        Plan shown = ops.targetsPlan();
        return shown == null || !PlanViewModel.showsTarget(item) ? "" : PlanViewModel.targetText(shown.projectionOf(item));
    }

    // ---- engine operations (see PlanOperations) ----

    @FXML
    protected void onPrepareClick() {
        if (loading || excluding) {
            return;
        }
        ops.prepare();
    }

    @Override
    public void scrollToResume() {
        if (!rows.isEmpty()) {
            // the index is in resume order, the table may be sorted otherwise: scroll to that row
            itemsTable.scrollTo(rows.get(Math.min(PlanViewModel.resumeScrollIndex(rows, RESUME_CONTEXT_ROWS), rows.size() - 1)));
        }
    }

    /** Rows already imported before the first one to copy, kept in view above it. */
    private static final int RESUME_CONTEXT_ROWS = 5;

    @Override
    public void showError(Throwable failure) {
        PopinUtil.showError(failure);
    }

    @FXML
    protected void onCopyClick() {
        ops.copy();
    }

    @FXML
    protected void onPauseClick() {
        ops.pause();
    }

    @FXML
    protected void onResumeClick() {
        ops.resume();
    }

    @FXML
    protected void onStopClick() {
        ops.stop();
    }

    @FXML
    protected void onAutoExecuteClick() {
        model().setAutoExecute(autoExecuteBox.isSelected()); // this session only: the file is not changed
    }

    @FXML
    protected void onBackClick() {
        if (!ops.busy() && model().canGoBack()) {
            main.showHome();
        }
    }

    @FXML
    protected void onEditClick() {
        if (ops.busy() || excluding || !model().canGoBack()) {
            return;
        }
        // returns at once: the editor opens once the file is read (Prepare and Copy stay clickable
        // meanwhile), so a save may come while the engine is held: it is then applied once it is free
        EditorController.open(CopybotMainUi.STAGE, pipelinePath, saved -> {
            if (!isShown()) {
                return; // this view was replaced while the editor was loading
            }
            ops.editorSaved(saved);
        });
    }

    /**
     * A no-op once this view is no longer shown: the editor opens once its file is read, and Back (or a
     * language change) may have replaced this view meanwhile.
     */
    @Override
    public void applySaved(Path saved) {
        if (!isShown()) {
            return;
        }
        if (saved.toAbsolutePath().normalize().equals(pipelinePath)) {
            reload();
        } else {
            main.showPlan(saved); // "save as": the plan view follows the new file
        }
    }

    /** This view is still the one of the main window (not replaced by the home screen or a rebuild). */
    private boolean isShown() {
        return backButton.getScene() != null && backButton.getScene() == CopybotMainUi.STAGE.getScene();
    }

    // ---- context menu of the rows ----

    /**
     * "Resume from here" and "Planned processing…" for the clicked row; ignore, include again, always ignore for
     * the selected rows.
     */
    private TableRow<WorkItemExecution> rowWithMenu() {
        TableRow<WorkItemExecution> row = new TableRow<>();
        MenuItem fromHere = new MenuItem(ResourcesEngine.getString("plan.menu.resume-from-here"));
        fromHere.setOnAction(e -> {
            if (row.getItem() != null) {
                model().resumePointFrom(row.getItem()).ifPresent(this::resumeFrom);
            }
        });
        MenuItem detail = new MenuItem();
        detail.setOnAction(e -> showDetails(PlanViewModel.detailable(selectedRows()), row.getItem()));
        MenuItem analyse = new MenuItem();
        analyse.setOnAction(e -> analyse(model().analysable(selectedRows())));
        MenuItem ignore = new MenuItem();
        ignore.setOnAction(e -> ignoreForThisRun(model().ignorable(selectedRows()), true));
        MenuItem unignore = new MenuItem();
        unignore.setOnAction(e -> ignoreForThisRun(model().unignorable(selectedRows()), false));
        MenuItem exclude = new MenuItem();
        exclude.setOnAction(e -> alwaysIgnore(selectedRows()));
        ContextMenu menu = new ContextMenu(fromHere, detail, analyse, new SeparatorMenuItem(), ignore, unignore, exclude);
        menu.setOnShowing(e -> {
            PlanViewModel model = model();
            boolean busy = ops.busy();
            fromHere.setDisable(row.getItem() == null || busy || model.resumePointFrom(row.getItem()).isEmpty());
            List<WorkItemExecution> selected = selectedRows();
            // both apply to the selection, whatever the clicked row
            setCountedItem(detail, "plan.menu.details", PlanViewModel.detailable(selected).size());
            setCountedItem(analyse, "plan.menu.analyse", busy ? 0 : model.analysable(selected).size());
            setCountedItem(ignore, "plan.menu.ignore", busy ? 0 : model.ignorable(selected).size());
            setCountedItem(unignore, "plan.menu.unignore", busy ? 0 : model.unignorable(selected).size());
            setCountedItem(exclude, "plan.menu.exclude", busy || excluding ? 0 : model.excludable(selected).size());
        });
        row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                .then((ContextMenu) null).otherwise(menu));
        return row;
    }

    /** A magnifier, drawn (an emoji depends on the fonts of the system). */
    private static final String MAGNIFIER = "M5 0a5 5 0 0 1 4.03 7.96l2.97 2.97-1.06 1.06-2.97-2.97A5 5 0 1 1 5 0zm0 1.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z";

    /**
     * The name, then on the right a magnifier opening the planned processing: shown only for the files analysed,
     * so that it also tells which ones were (the files skipped at the listing have none).
     */
    private final class NameCell extends TableCell<WorkItemExecution, String> {
        private final Label name = new Label();
        private final Label magnifier = new Label();
        private final HBox box = new HBox(4.0, name, magnifier);

        NameCell() {
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
            icon.setContent(MAGNIFIER);
            icon.setStyle("-fx-fill: #6b7280;");
            magnifier.setGraphic(icon);
            magnifier.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            magnifier.setCursor(javafx.scene.Cursor.HAND);
            magnifier.setTooltip(new Tooltip(ResourcesEngine.getString("plan.menu.detail")));
            magnifier.setOnMouseClicked(e -> {
                WorkItemExecution item = getTableRow() == null ? null : getTableRow().getItem();
                if (item != null) {
                    showDetail(item);
                }
                e.consume();
            });
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(String text, boolean empty) {
            super.updateItem(text, empty);
            setText(null);
            WorkItemExecution item = empty || getTableRow() == null ? null : getTableRow().getItem();
            if (item == null) {
                setGraphic(null);
                return;
            }
            name.setText(text);
            boolean analysed = item.getProjection() != null; // detailOf is empty exactly without projection
            magnifier.setVisible(analysed);
            magnifier.setManaged(analysed);
            setGraphic(box);
        }
    }

    /** The planned processing of this item, read only: available as soon as it is analysed, and after the copy. */
    @Override
    public void showDetail(WorkItemExecution item) {
        showDetails(List.of(item), item);
    }

    /** The planned processing of these analysed items, from the clicked one when it is among them. */
    private void showDetails(List<WorkItemExecution> items, WorkItemExecution clicked) {
        Plan shown = ops.targetsPlan();
        if (shown == null || items.isEmpty()) {
            return;
        }
        ItemDetailDialog.show(shown, items, clicked);
    }

    /** The label with the number of rows it applies to, disabled for none. */
    private static void setCountedItem(MenuItem item, String key, int count) {
        item.setText(ResourcesEngine.getString(key, count));
        item.setDisable(count == 0);
    }

    /** The selected rows (a right click selects the clicked row when it is not selected yet). */
    private List<WorkItemExecution> selectedRows() {
        return List.copyOf(itemsTable.getSelectionModel().getSelectedItems());
    }

    /** In memory on the prepared, idle plan, like a resume point: nothing is executed. */
    private void ignoreForThisRun(List<WorkItemExecution> items, boolean ignore) {
        Plan plan = ops.plan();
        if (plan == null || ops.busy() || items.isEmpty()) {
            return;
        }
        try {
            if (ignore) {
                plan.ignore(items, model().override());
            } else {
                plan.unignore(items, model().override());
            }
            model().update(plan.getState(), plan.getOrderedItems());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
        refresh();
    }

    /**
     * Excludes the files of these rows from the pipeline for good ("exclude" globs of its file.read steps), after
     * a confirmation; in a prepared plan they are also ignored for this run, so it need not be prepared again.
     */
    private void alwaysIgnore(List<WorkItemExecution> rows) {
        List<Path> files = model().excludable(rows);
        if (files.isEmpty() || ops.busy() || excluding) {
            return;
        }
        // the file is read and written in the background: no preparation nor edit meanwhile
        excluding = true;
        Plan target = ops.plan();
        refresh();
        Background.run(() -> {
                    PipelineDocument document = PipelineDocument.load(pipelinePath);
                    return new Excluding(document, document.exclude(files));
                },
                read -> {
                    if (read.exclusion().added().isEmpty()) {
                        informExclusion(read.exclusion());
                        excluded(target, rows, read.exclusion());
                    } else if (confirmExclusion(read.document(), read.exclusion())) {
                        Background.run(() -> {
                                    read.document().save(pipelinePath);
                                    return null;
                                },
                                saved -> excluded(target, rows, read.exclusion()),
                                this::exclusionFailed);
                    } else {
                        excluding = false;
                        refresh();
                    }
                },
                this::exclusionFailed);
    }

    /** The pipeline read for an exclusion, with the exclusion applied to it (not saved yet). */
    private record Excluding(PipelineDocument document, PipelineDocument.Exclusion exclusion) {
    }

    /** The files are excluded in the pipeline: in the plan they were shown from, they are ignored for this run too. */
    private void excluded(Plan target, List<WorkItemExecution> rows, PipelineDocument.Exclusion exclusion) {
        excluding = false;
        List<WorkItemExecution> excluded = rows.stream()
                .filter(r -> r.getListedPath().filter(p -> !exclusion.uncovered().contains(p)).isPresent())
                .toList();
        Plan plan = ops.plan();
        if (plan != null && plan == target && !ops.busy()) {
            try {
                List<WorkItemExecution> toIgnore = model().ignorable(excluded);
                if (!toIgnore.isEmpty()) {
                    plan.ignore(toIgnore, model().override());
                    model().update(plan.getState(), plan.getOrderedItems());
                }
            } catch (RuntimeException e) {
                PopinUtil.showError(e);
            }
        }
        refresh();
    }

    /** Unreadable pipeline, failed save: the file is left as it was. */
    private void exclusionFailed(Throwable failure) {
        excluding = false;
        refresh();
        PopinUtil.showError(failure);
    }

    /** The globs about to be added, the files no step lists, the loss of a non-strict file's comments. */
    private boolean confirmExclusion(PipelineDocument document, PipelineDocument.Exclusion exclusion) {
        List<String> parts = new ArrayList<>();
        parts.add(ResourcesEngine.getString("plan.exclude.confirm", exclusion.added().size(),
                RecentPipelines.displayName(pipelinePath)) + "\n" + String.join("\n", exclusion.added()));
        uncoveredText(exclusion).ifPresent(parts::add);
        if (document.isLenient()) {
            parts.add(ResourcesEngine.getString("editor.lenient"));
        }
        ButtonType add = new ButtonType(ResourcesEngine.getString("plan.exclude.add"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, String.join("\n\n", parts), add, ButtonType.CANCEL);
        alert.initOwner(CopybotMainUi.STAGE);
        alert.setTitle(ResourcesEngine.getString("plan.exclude.title"));
        alert.setHeaderText(null);
        return alert.showAndWait().filter(choice -> choice == add).isPresent();
    }

    /** Nothing added: the files are already excluded, or no step lists them. */
    private void informExclusion(PipelineDocument.Exclusion exclusion) {
        String text = uncoveredText(exclusion).orElse(ResourcesEngine.getString("plan.exclude.already"));
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.initOwner(CopybotMainUi.STAGE);
        alert.setTitle(ResourcesEngine.getString("plan.exclude.title"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static Optional<String> uncoveredText(PipelineDocument.Exclusion exclusion) {
        if (exclusion.uncovered().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ResourcesEngine.getString("plan.exclude.uncovered") + "\n"
                + exclusion.uncovered().stream().map(Path::toString).collect(Collectors.joining("\n")));
    }

    // ---- resume point ----

    @FXML
    protected void onChangeResumeClick() {
        if (ops.busy()) {
            return;
        }
        PlanViewModel model = model();
        if (ops.plan() != null && model.canChangeResumePoint()) {
            ResumeDialog.create(model.items(), model.chosenPoint(), false).showAndWait()
                    .ifPresent(choice -> applyResumePoint(choice.point()));
        } else if (model.canChooseResumePoint()) {
            // before a prepared plan (spec manual-point §3): the point of the next preparation, nothing more; a
            // stopped preparation is not resumed by itself (Prepare starts again from the point)
            ResumeDialog.create(model.items(), model.chosenPoint(), true).showAndWait().ifPresent(choice -> {
                model().setChosenPoint(choice.point());
                refresh();
            });
        }
    }

    /**
     * "Resume from here": on a prepared plan, a manual point; on a preparation stopped after its listing, the point of
     * the next preparation only: the analysis the user stopped does not start again by itself.
     */
    private void resumeFrom(ResumePoint point) {
        if (model().isStoppedAfterTheListing()) {
            model().setChosenPoint(point);
            refresh();
        } else {
            applyResumePoint(point);
        }
    }

    /** In memory on the prepared, idle plan: the statuses are recomputed, nothing is executed. */
    private void applyResumePoint(ResumePoint point) {
        Plan plan = ops.plan();
        if (plan == null || !model().canChangeResumePoint()) {
            return;
        }
        try {
            plan.preview(point);
            model().setOverride(point);
            model().update(plan.getState(), plan.getOrderedItems());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
        refresh();
        scrollToResume(); // the junction moved
        List<WorkItemExecution> toAnalyse = plan.toAnalyse();
        if (!toAnalyse.isEmpty()) {
            ops.analyse(toAnalyse, null);
        }
    }

    /** "Analyse" on skipped files not analysed: they stay skipped, their planned processing becomes known. */
    private void analyse(List<WorkItemExecution> items) {
        Plan plan = ops.plan();
        if (plan == null || items.isEmpty() || ops.busy()) {
            return;
        }
        plan.requestAnalysis(items);
        List<WorkItemExecution> toAnalyse = plan.toAnalyse();
        if (!toAnalyse.isEmpty()) {
            // one file asked for: its planned processing opens once analysed
            ops.analyse(toAnalyse, items.size() == 1 ? items.getFirst() : null);
        }
    }

    // ---- rendering ----

    private void refresh() {
        ops.refresh(); // renders, then applies an editor save received while the engine was held
    }

    @Override
    public void render() {
        PlanViewModel model = model();
        Phase phase = model.phase();
        boolean busy = ops.busy();
        backButton.setDisable(busy || !model.canGoBack());
        editButton.setDisable(busy || excluding || !model.canGoBack());
        prepareButton.setText(model.prepareLabel());
        prepareButton.setDisable(loading || excluding || busy || !model.canPrepare());

        show(copyButton, phase == Phase.PREPARED || phase == Phase.ANALYSING); // disabled while analysing
        copyButton.setText(model.copyLabel());
        copyButton.setDisable(busy || !model.canCopy());
        show(autoExecuteBox, model.showsAutoExecuteBox());
        // text, not a tooltip: a disabled button gets no mouse event, so its tooltip never shows
        Optional<String> warning = model.warning();
        warningLabel.setText(warning.map(w -> "⚠ " + w).orElse(""));
        show(warningLabel, warning.isPresent());
        show(pauseButton, model.isExecutionActive() && !model.canResume());
        show(resumeButton, model.canResume());
        show(stopButton, phase == Phase.PREPARING || model.isExecutionActive() || phase == Phase.ANALYSING);
        pauseButton.setDisable(!model.canPause());
        // a preparation is stopped through its plan, handed out once the pipeline file is read
        stopButton.setDisable(!model.canStop() || phase == Phase.PREPARING && !ops.isPreparationStoppable());

        boolean preparing = phase == Phase.PREPARING || phase == Phase.ANALYSING; // the same bar
        show(progressBox, preparing || model.isExecutionActive() || phase == Phase.FINISHED);
        gauges.refresh(preparing || model.isExecutionActive());
        progressBar.setProgress(preparing ? model.prepareFraction() : model.executionFraction());
        progressLabel.setText(model.progressText());
        statusLine.setText(model.statusLine());

        List<String> warnings = model.warnings(); // the steps' configuration warnings, then the resume ones
        warningBanner.setText(String.join("\n", warnings));
        show(warningBanner, !warnings.isEmpty());
        Optional<String> resume = model.resumeText();
        resumeLabel.setText(resume.orElse(""));
        show(resumeBox, resume.isPresent());
        Optional<String> resumeCount = model.resumeCountText();
        resumeCountLabel.setText(resumeCount.orElse(""));
        show(resumeCountLabel, resumeCount.isPresent());
        Optional<String> conflicts = model.conflictText();
        conflictLabel.setText(conflicts.orElse(""));
        show(conflictLabel, conflicts.isPresent());
        changeResumeLink.setDisable(busy || !model.canChangeResumePoint() && !model.canChooseResumePoint());

        placeholder.setText(phase == Phase.NOT_PREPARED ? ResourcesEngine.getString("plan.placeholder") : "");
        List<WorkItemExecution> visible = model.visibleItems();
        if (sameRows(visible)) {
            itemsTable.refresh(); // same rows, their status or percent evolved
        } else {
            rows.setAll(visible);
        }

        main.setBusy(busy);
        model.consumeFinishedRun(Instant.now()).ifPresent(run -> {
            UiPreferences.updateRecents(recents -> recents.recordRun(pipelinePath, run));
            renderSummary(); // its "last run" line
        });
    }

    private boolean sameRows(List<WorkItemExecution> visible) {
        if (visible.size() != rows.size()) {
            return false;
        }
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i) != rows.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** A cell of a column whose value is the row itself (sorted by value): shows its text. */
    private static TableCell<WorkItemExecution, WorkItemExecution> textCell(Function<WorkItemExecution, String> text) {
        return new TableCell<>() {
            @Override
            protected void updateItem(WorkItemExecution item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        };
    }

    static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
