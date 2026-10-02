package com.copybot.ui;

import com.copybot.engine.Execution;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineSummary;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.PlanViewModel.Filter;
import com.copybot.ui.model.PlanViewModel.Phase;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.model.StepCatalog;
import com.copybot.ui.util.PopinUtil;
import com.copybot.ui.util.UiPreferences;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * The plan view of one pipeline (spec desktop-ui §2), a thin layer over {@link PlanViewModel}: it starts
 * the engine operations on background threads ({@link CopybotMainUi#executor}), feeds the model on the
 * JavaFX thread and renders it.
 * <p>
 * Every preparation gets its own operation token: a notification (watcher, end of an operation) carrying
 * another token, or the state of another plan, comes from an earlier run and is dropped.
 */
public class PlanController {

    /** The date of a resume key in the "from a file" list (like the Date column). */
    private static final DateTimeFormatter RESUME_KEY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @FXML private Button backButton;
    @FXML private Label pipelineName;
    @FXML private Button editButton;
    @FXML private Label summaryLabel;
    @FXML private Label warningBanner;
    @FXML private HBox resumeBox;
    @FXML private Label resumeLabel;
    @FXML private Hyperlink changeResumeLink;
    @FXML private ComboBox<Filter> filterCombo;
    @FXML private TableView<WorkItemExecution> itemsTable;
    @FXML private TableColumn<WorkItemExecution, String> nameColumn;
    @FXML private TableColumn<WorkItemExecution, String> dateColumn;
    @FXML private TableColumn<WorkItemExecution, String> targetColumn;
    @FXML private TableColumn<WorkItemExecution, String> sizeColumn;
    @FXML private TableColumn<WorkItemExecution, String> statusColumn;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
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
    private PlanViewModel model = new PlanViewModel(false);
    private PipelineSummary summary;
    /** The pipeline file is being read (background): no preparation meanwhile. */
    private boolean loading;
    private Object loadToken;
    /** The token of the current preparation and of the execution of its plan. */
    private Object operation = new Object();
    private Plan plan;
    /** The plan being prepared, until {@code prepare()} returns: the targets of the items analysed so far. */
    private Plan preparingPlan;
    /** The execution of the plan, completed once the engine accepted it (null before the copy). */
    private CompletableFuture<Execution> execution;
    /**
     * The engine is still held by an operation started here: from Prepare until {@code prepare()} has
     * returned, from Copy until the execution is really over ({@code Execution.await()}). The model may
     * already be PREPARED or FINISHED (terminal watcher notification) while the engine is not free yet.
     */
    private boolean engineHeld;
    /** The token of the operation holding the engine: only its own end releases it (not renewed by a reload). */
    private Object holdToken;
    /** A save of the editor received while the engine was held: applied once it is free. */
    private Path pendingSaved;

    @FXML
    public void initialize() {
        itemsTable.setItems(rows);
        itemsTable.setPlaceholder(placeholder);
        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getWorkItem().getNameDisplay()));
        dateColumn.setCellValueFactory(c -> new SimpleStringProperty(PlanViewModel.dateText(c.getValue())));
        targetColumn.setCellValueFactory(c -> new SimpleStringProperty(targetText(c.getValue())));
        targetColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                WorkItemExecution item = empty || getTableRow() == null ? null : getTableRow().getItem();
                Plan shown = targetsPlan();
                String tooltip = item == null || shown == null ? null : PlanViewModel.targetTooltip(shown.projectionOf(item));
                setTooltip(tooltip == null ? null : new Tooltip(tooltip));
            }
        });
        sizeColumn.setCellValueFactory(c -> new SimpleStringProperty(PlanViewModel.sizeText(c.getValue())));
        statusColumn.setCellValueFactory(c -> new SimpleStringProperty(PlanViewModel.statusText(c.getValue())));
        itemsTable.setRowFactory(table -> resumeFromHereRow());

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
            model.setFilter(filter == null ? Filter.ALL : filter);
            refresh();
        });
    }

    void init(MainController main, Path pipelinePath) {
        this.main = main;
        this.pipelinePath = pipelinePath;
        pipelineName.setText(RecentPipelines.displayName(pipelinePath));
        reload();
    }

    /**
     * (Re)reads the pipeline in the background: the prepared plan, if any, is dropped (spec desktop-ui §3).
     * Only called while the engine is idle (Edit is disabled while busy).
     */
    private void reload() {
        operation = new Object(); // a late notification of the dropped plan is ignored
        plan = null;
        preparingPlan = null;
        execution = null;
        model = new PlanViewModel(false);
        model.setFilter(filterCombo.getValue() == null ? Filter.ALL : filterCombo.getValue());
        autoExecuteBox.setSelected(false);
        summary = null;
        loading = true;
        Object token = new Object();
        loadToken = token;
        renderSummary();
        refresh();
        try {
            CopybotMainUi.executor.submit(() -> {
                PipelineDocument document;
                try {
                    document = PipelineDocument.load(pipelinePath);
                } catch (Throwable t) { // an Error too: the view must not stay "loading"
                    Platform.runLater(() -> {
                        if (token == loadToken) {
                            loading = false;
                            refresh();
                            PopinUtil.showError(asException(t)); // the preparation will report it too
                        }
                    });
                    return;
                }
                // the catalog (plugin instances, bundles) is built here too, off the JavaFX thread
                PipelineSummary loadedSummary = null;
                Exception summaryFailure = null;
                try {
                    loadedSummary = PipelineSummary.of(document, new StepCatalog(PluginEngine.catalog()));
                } catch (Throwable t) {
                    summaryFailure = asException(t);
                }
                PipelineSummary result = loadedSummary;
                Exception failure = summaryFailure;
                Platform.runLater(() -> onLoaded(token, document, result, failure));
            });
        } catch (RejectedExecutionException e) {
            loading = false; // the application is closing
        }
    }

    private void onLoaded(Object token, PipelineDocument document, PipelineSummary loaded, Exception failure) {
        if (token != loadToken) {
            return;
        }
        loading = false;
        model.setPreparationRefusal(document.gaps().contains(PipelineDocument.Gap.NO_INPUT)
                ? Optional.of(PipelineDocument.Gap.NO_INPUT.message()) : Optional.empty());
        model.setAutoExecute(document.autoExecute());
        autoExecuteBox.setSelected(model.isAutoExecute());
        summary = loaded;
        renderSummary();
        refresh();
        if (failure != null) {
            PopinUtil.showError(failure);
        }
    }

    /** Steps, source, output pattern, resume mode, last run. */
    private void renderSummary() {
        if (summary == null) {
            summaryLabel.setText("");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(ResourcesEngine.getString("plan.steps", String.join(" → ", summary.stepNames())));
        if (summary.sourcePath() != null) {
            lines.add(ResourcesEngine.getString("plan.source", summary.sourcePath()));
        }
        if (summary.outPattern() != null) {
            lines.add(ResourcesEngine.getString("plan.out-pattern", summary.outPattern()));
        }
        lines.add(ResourcesEngine.getString("plan.resume-mode", PlanViewModel.resumeModeText(summary.resumeMode())));
        RecentPipelines.LastRun lastRun = UiPreferences.recents().entries().stream()
                .filter(e -> e.path().equals(pipelinePath.toAbsolutePath().normalize()))
                .findFirst()
                .map(RecentPipelines.Entry::lastRun) // null when never run: "never run"
                .orElse(null);
        lines.add(ResourcesEngine.getString("plan.last-run", PlanViewModel.lastRunText(lastRun)));
        summaryLabel.setText(String.join("\n", lines));
    }

    private String targetText(WorkItemExecution item) {
        Plan shown = targetsPlan();
        return shown == null ? "" : PlanViewModel.targetText(shown.projectionOf(item));
    }

    /** The prepared plan, or while preparing the plan being prepared (its targets fill in as items are analysed). */
    private Plan targetsPlan() {
        return plan != null ? plan : preparingPlan;
    }

    private List<WorkItemExecution> ordered() {
        return plan == null ? List.of() : plan.getOrderedItems();
    }

    // ---- engine operations (background threads; the model and the scene graph on the JavaFX thread) ----

    /** A preparation or an execution holds the engine, or the model says one is running. */
    private boolean busy() {
        return engineHeld || model.isActive();
    }

    private Object hold() {
        Object hold = new Object();
        holdToken = hold;
        engineHeld = true;
        return hold;
    }

    /** The operation of this hold has really ended in the engine (the caller refreshes). */
    private void release(Object hold) {
        if (hold == holdToken) {
            engineHeld = false;
            holdToken = null;
        }
    }

    @FXML
    protected void onPrepareClick() {
        if (loading || busy() || !model.canPrepare()) {
            return;
        }
        Object op = new Object();
        operation = op;
        Object hold = hold();
        plan = null;
        preparingPlan = null;
        execution = null;
        model.startPreparing();
        refresh();
        try {
            CopybotMainUi.executor.submit(() -> {
                Plan prepared;
                try {
                    prepared = CopybotMainUi.ENGINE.prepare(pipelinePath, state -> onState(op, state),
                            started -> Platform.runLater(() -> onPrepareStarted(op, started)));
                } catch (Throwable t) { // missing or invalid pipeline file, engine busy or closed, an Error
                    Platform.runLater(() -> onPrepareRefused(op, hold, t));
                    return;
                }
                Platform.runLater(() -> onPrepared(op, hold, prepared));
            });
        } catch (RejectedExecutionException e) {
            onPrepareRefused(op, hold, e); // the application is closing
        }
    }

    /** The watcher notifications that follow refresh the table, so its targets fill in. */
    private void onPrepareStarted(Object op, Plan started) {
        if (op == operation && plan == null) {
            preparingPlan = started;
        }
    }

    private void onPrepareRefused(Object op, Object hold, Throwable failure) {
        release(hold); // prepare() has returned (thrown)
        if (op == operation) {
            preparingPlan = null;
            model.reset();
        }
        refresh();
        PopinUtil.showError(asException(failure));
    }

    /** An Error (e.g. a LinkageError of a plugin) shown like an exception. */
    private static Exception asException(Throwable failure) {
        return failure instanceof Exception e ? e : new IllegalStateException(failure.toString(), failure);
    }

    /** Watcher notifications (background thread, coalesced ~10 Hz) of the preparation and of the execution. */
    private void onState(Object op, PipelineState state) {
        Platform.runLater(() -> {
            if (op != operation || (plan != null && state != plan.getState())) {
                return; // an earlier run: it must not change the phase
            }
            model.update(state, ordered());
            afterUpdate();
        });
    }

    private void onPrepared(Object op, Object hold, Plan prepared) {
        release(hold); // prepare() has returned: the engine is free
        if (op != operation) {
            refresh();
            return;
        }
        plan = prepared;
        preparingPlan = null;
        model.setExecutionRefusal(prepared.executionRefusal());
        model.update(prepared.getState(), prepared.getOrderedItems());
        afterUpdate();
        scrollToResume();
    }

    /** Rows already imported before the first one to copy, kept in view above it. */
    private static final int RESUME_CONTEXT_ROWS = 5;

    /** Shows the resume junction: the last imported files, then the first ones to copy. */
    private void scrollToResume() {
        if (!rows.isEmpty()) {
            itemsTable.scrollTo(PlanViewModel.resumeScrollIndex(rows, RESUME_CONTEXT_ROWS));
        }
    }

    /**
     * Renders, then starts the copy when the plan just became ready and "automatic execution" is checked.
     * Checked after every update, but only once the plan is known: the terminal watcher notification of
     * the preparation may come before {@code prepare} returns.
     */
    private void afterUpdate() {
        refresh();
        if (plan != null && model.consumeAutoExecute()) {
            startCopy();
        }
    }

    @FXML
    protected void onCopyClick() {
        startCopy();
    }

    /** Only from PREPARED; when the engine refuses the plan the view goes back to the prepared plan. */
    private void startCopy() {
        if (plan == null || engineHeld || model.phase() != Phase.PREPARED || !model.canCopy()) {
            return;
        }
        Object op = operation;
        Object hold = hold();
        Plan toRun = plan;
        ResumePoint override = model.override();
        model.startExecuting(); // before execute(): its first notifications must find the model executing
        CompletableFuture<Execution> future = new CompletableFuture<>();
        execution = future;
        refresh();
        try {
            CopybotMainUi.executor.submit(() -> {
                Execution run;
                try {
                    run = CopybotMainUi.ENGINE.execute(toRun, override); // the preparation's watcher goes on
                } catch (Throwable t) { // refused (engine busy or closed), or an Error
                    future.completeExceptionally(t);
                    Platform.runLater(() -> onExecuteRefused(op, hold, future, toRun, override, t));
                    return;
                }
                future.complete(run);
                Throwable awaitFailure = null;
                try {
                    run.await(); // returns once the engine has released the operation
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // the application is closing: release anyway
                } catch (Throwable t) {
                    awaitFailure = t;
                }
                Throwable failure = awaitFailure;
                Platform.runLater(() -> { // never leave the view held, whatever happened
                    release(hold);
                    if (failure != null && op == operation && future == execution) {
                        model.update(run.getState(), ordered());
                        if (model.isActive()) { // no terminal state will come: the plan is dropped
                            model.reset();
                            plan = null;
                            execution = null;
                        }
                    }
                    refresh();
                    if (failure != null) {
                        PopinUtil.showError(asException(failure));
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
            onExecuteRefused(op, hold, future, toRun, override, e); // the application is closing
        }
    }

    private void onExecuteRefused(Object op, Object hold, CompletableFuture<Execution> future, Plan toRun,
                                  ResumePoint override, Throwable e) {
        release(hold);
        if (op == operation && future == execution) {
            // a fresh model on the same plan: the phase follows the plan's state again (PREPARED, not locked)
            PlanViewModel back = new PlanViewModel(model.isAutoExecute());
            back.setFilter(model.filter());
            back.setOverride(override);
            back.update(toRun.getState(), toRun.getOrderedItems());
            model = back;
            execution = null;
        }
        refresh();
        PopinUtil.showError(asException(e));
    }

    @FXML
    protected void onPauseClick() {
        control(Execution::pause);
    }

    @FXML
    protected void onResumeClick() {
        control(Execution::resume);
    }

    @FXML
    protected void onStopClick() {
        control(Execution::cancel);
    }

    /**
     * Pause, resume or stop, in the background once the engine accepted the execution; a pause or resume
     * changes the status without a watcher notification, hence the update afterwards.
     */
    private void control(Consumer<Execution> action) {
        CompletableFuture<Execution> future = execution;
        if (future == null || !model.isExecutionActive()) {
            return;
        }
        Object op = operation;
        future.thenAcceptAsync(running -> {
            action.accept(running);
            Platform.runLater(() -> {
                if (op == operation && future == execution) {
                    model.update(running.getState(), ordered());
                    refresh();
                }
            });
        }, CopybotMainUi.executor).exceptionally(failure -> {
            if (!future.isCompletedExceptionally()) { // a refused execute() is already reported by onExecuteRefused
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                Exception shown = cause instanceof Exception ex ? ex : new RuntimeException(cause);
                Platform.runLater(() -> PopinUtil.showError(shown));
            }
            return null;
        });
    }

    @FXML
    protected void onAutoExecuteClick() {
        model.setAutoExecute(autoExecuteBox.isSelected()); // this session only: the file is not changed
    }

    @FXML
    protected void onBackClick() {
        if (!busy() && model.canGoBack()) {
            main.showHome();
        }
    }

    @FXML
    protected void onEditClick() {
        if (busy() || !model.canGoBack()) {
            return;
        }
        // returns at once: the editor opens once the file is read (Prepare and Copy stay clickable
        // meanwhile), so a save may come while the engine is held: it is then applied once it is free
        EditorController.open(CopybotMainUi.STAGE, pipelinePath, saved -> {
            if (!isShown()) {
                return; // this view was replaced while the editor was loading
            }
            if (busy()) {
                pendingSaved = saved;
            } else {
                applySaved(saved);
            }
        });
    }

    /**
     * A no-op once this view is no longer shown: the editor opens once its file is read, and Back (or a
     * language change) may have replaced this view meanwhile.
     */
    private void applySaved(Path saved) {
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

    // ---- resume point ----

    private TableRow<WorkItemExecution> resumeFromHereRow() {
        TableRow<WorkItemExecution> row = new TableRow<>();
        MenuItem fromHere = new MenuItem(ResourcesEngine.getString("plan.menu.resume-from-here"));
        fromHere.setOnAction(e -> {
            if (row.getItem() != null) {
                model.resumePointFrom(row.getItem()).ifPresent(this::applyResumePoint);
            }
        });
        ContextMenu menu = new ContextMenu(fromHere);
        menu.setOnShowing(e -> fromHere.setDisable(row.getItem() == null || model.resumePointFrom(row.getItem()).isEmpty()));
        row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                .then((ContextMenu) null).otherwise(menu));
        return row;
    }

    @FXML
    protected void onChangeResumeClick() {
        if (plan != null && model.canChangeResumePoint()) {
            resumeDialog().showAndWait().ifPresent(this::applyResumePoint);
        }
    }

    /** In memory on the prepared, idle plan: the statuses are recomputed, nothing is executed. */
    private void applyResumePoint(ResumePoint point) {
        if (plan == null || !model.canChangeResumePoint()) {
            return;
        }
        try {
            plan.preview(point);
            model.setOverride(point);
            model.update(plan.getState(), plan.getOrderedItems());
        } catch (RuntimeException e) {
            PopinUtil.showError(e);
        }
        refresh();
        scrollToResume(); // the junction moved
    }

    /** Everything / from a date / from a file of the plan. */
    private Dialog<ResumePoint> resumeDialog() {
        Dialog<ResumePoint> dialog = new Dialog<>();
        dialog.initOwner(CopybotMainUi.STAGE);
        dialog.setTitle(ResourcesEngine.getString("resume.dialog.title"));
        ToggleGroup group = new ToggleGroup();
        RadioButton all = new RadioButton(ResourcesEngine.getString("resume.dialog.all"));
        RadioButton fromDate = new RadioButton(ResourcesEngine.getString("resume.dialog.date"));
        RadioButton fromFile = new RadioButton(ResourcesEngine.getString("resume.dialog.file"));
        List.of(all, fromDate, fromFile).forEach(b -> b.setToggleGroup(group));
        all.setSelected(true);
        DatePicker date = new DatePicker(LocalDate.now());
        // the keys themselves, "name (date)": two files of the same name (counter rollover) stay distinct
        ComboBox<ItemKey> file = new ComboBox<>();
        file.setConverter(new StringConverter<>() {
            @Override
            public String toString(ItemKey key) {
                return key == null ? "" : key.name() + " (" + RESUME_KEY_DATE.format(key.date().atZone(ZoneId.systemDefault())) + ")";
            }

            @Override
            public ItemKey fromString(String s) {
                return null; // not editable
            }
        });
        plan.getOrderedItems().stream()
                .map(WorkItemExecution::getResumeKey)
                .flatMap(Optional::stream)
                .forEach(file.getItems()::add);
        if (!file.getItems().isEmpty()) {
            file.setValue(file.getItems().getFirst());
        }
        date.disableProperty().bind(fromDate.selectedProperty().not());
        file.disableProperty().bind(fromFile.selectedProperty().not());
        VBox content = new VBox(8, all, new HBox(8, fromDate, date), new HBox(8, fromFile, file));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(fromFile.selectedProperty().and(file.valueProperty().isNull())
                .or(fromDate.selectedProperty().and(date.valueProperty().isNull())));
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            if (fromDate.isSelected()) {
                return Plan.fromDate(date.getValue());
            }
            if (fromFile.isSelected()) {
                return ResumePoint.from(file.getValue());
            }
            return ResumePoint.all();
        });
        return dialog;
    }

    // ---- rendering ----

    private void refresh() {
        Phase phase = model.phase();
        boolean busy = busy();
        backButton.setDisable(busy || !model.canGoBack());
        editButton.setDisable(busy || !model.canGoBack());
        prepareButton.setDisable(loading || busy || !model.canPrepare());

        show(copyButton, phase == Phase.PREPARED);
        copyButton.setText(model.copyLabel());
        copyButton.setDisable(busy || !model.canCopy());
        show(autoExecuteBox, !model.isExecutionActive());
        // text, not a tooltip: a disabled button gets no mouse event, so its tooltip never shows
        Optional<String> warning = model.warning();
        warningLabel.setText(warning.map(w -> "⚠ " + w).orElse(""));
        show(warningLabel, warning.isPresent());
        show(pauseButton, model.isExecutionActive() && !model.canResume());
        show(resumeButton, model.canResume());
        show(stopButton, model.isExecutionActive());
        pauseButton.setDisable(!model.canPause());
        stopButton.setDisable(!model.canStop());

        boolean preparing = phase == Phase.PREPARING;
        show(progressBox, preparing || model.isExecutionActive() || phase == Phase.FINISHED);
        progressBar.setProgress(preparing ? model.prepareFraction() : model.progress().fraction());
        progressLabel.setText(model.progressText());
        statusLine.setText(model.statusLine());

        List<String> warnings = model.warnings(); // the steps' configuration warnings, then the resume ones
        warningBanner.setText(String.join("\n", warnings));
        show(warningBanner, !warnings.isEmpty());
        Optional<String> resume = model.resumeText();
        resumeLabel.setText(resume.orElse(""));
        show(resumeBox, resume.isPresent());
        changeResumeLink.setDisable(!model.canChangeResumePoint());

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
        if (!busy && pendingSaved != null) { // an editor save received while the engine was held
            Path saved = pendingSaved;
            pendingSaved = null;
            applySaved(saved); // last: it rebuilds this view (or replaces it)
        }
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

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
