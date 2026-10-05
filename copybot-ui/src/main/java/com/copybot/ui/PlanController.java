package com.copybot.ui;

import com.copybot.engine.Execution;
import com.copybot.engine.ItemDetail;
import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.ExecutionMode;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
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
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private PlanViewModel model = new PlanViewModel();
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
                Plan shown = targetsPlan();
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
        model = new PlanViewModel();
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

    /** Whether the details of the header are open: kept while the application runs, from one plan view to the next. */
    private static boolean detailsExpanded;

    private static final String CHIP_STYLE = "-fx-background-color: #f3f4f6; -fx-border-color: #d1d5db; "
            + "-fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 2 10 2 10;";
    private static final String MUTED_STYLE = "-fx-text-fill: #6b7280;";

    /** The card of the header: source and output, last run (badges), details (steps, resume mode) on demand. */
    private void renderSummary() {
        lastRunBox.getChildren().subList(1, lastRunBox.getChildren().size()).clear(); // after the key label
        stepsFlow.getChildren().clear();
        applyDetailsState();
        if (summary == null) {
            sourceLabel.setText("");
            sourceLabel.setTooltip(null);
            destinationLabel.setText("");
            destinationLabel.setTooltip(null);
            resumeModeLabel.setText("");
            return;
        }
        setPath(sourceLabel, "📂 ", summary.sourceText());
        setPath(destinationLabel, "💾 ", summary.destinationText());
        resumeModeLabel.setText(ResourcesEngine.getString("plan.resume-mode",
                PlanViewModel.resumeModeText(summary.resumeMode())));

        RecentPipelines.LastRun lastRun = UiPreferences.recents().entries().stream()
                .filter(e -> e.path().equals(pipelinePath.toAbsolutePath().normalize()))
                .findFirst()
                .map(RecentPipelines.Entry::lastRun) // null when never run
                .orElse(null);
        if (lastRun == null) {
            lastRunBox.getChildren().add(mutedLabel(ResourcesEngine.getString("recent.never-run")));
        } else {
            lastRunBox.getChildren().add(mutedLabel(PlanViewModel.lastRunDate(lastRun)));
            for (PlanViewModel.Badge badge : PlanViewModel.lastRunBadges(lastRun)) {
                lastRunBox.getChildren().add(badgeLabel(badge));
            }
        }

        boolean first = true;
        for (PipelineSummary.Step step : summary.steps()) {
            if (!first) {
                stepsFlow.getChildren().add(new Label("→"));
            }
            first = false;
            stepsFlow.getChildren().add(chip(step));
        }
    }

    private static void setPath(Label label, String icon, String value) {
        label.setText(icon + value);
        label.setTooltip(new Tooltip(value));
    }

    private static Label mutedLabel(String text) {
        Label label = new Label(text);
        label.setStyle(MUTED_STYLE);
        return label;
    }

    private static Label badgeLabel(PlanViewModel.Badge badge) {
        String colors = switch (badge.kind()) {
            case SUCCESS -> "-fx-background-color: #dcfce7; -fx-text-fill: #166534;";
            case ERROR -> "-fx-background-color: #fee2e2; -fx-text-fill: #991b1b;";
            case NEUTRAL -> "-fx-background-color: #e5e7eb; -fx-text-fill: #374151;";
        };
        Label label = new Label(badge.text());
        label.setStyle(colors + " -fx-background-radius: 10; -fx-padding: 1 8 1 8; -fx-font-size: 11px;");
        return label;
    }

    /** A step as a chip: its name behind the icon of its section, its settings in a tooltip. */
    private static Label chip(PipelineSummary.Step step) {
        String icon = switch (step.section()) {
            case IN -> "📂";
            case ANALYZE -> "🏷";
            case PROCESS -> "⚙";
            case OUT -> "💾";
        };
        Label chip = new Label(icon + " " + step.name());
        chip.setStyle(CHIP_STYLE);
        String text = step.settings().isEmpty()
                ? ResourcesEngine.getString("plan.chip.no-setting")
                : step.settings().stream().map(s -> s.label() + " : " + s.value()).collect(Collectors.joining("\n"));
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(250));
        tooltip.setShowDuration(Duration.INDEFINITE);
        chip.setTooltip(tooltip);
        return chip;
    }

    @FXML
    protected void onDetailsClick() {
        detailsExpanded = !detailsExpanded;
        applyDetailsState();
    }

    private void applyDetailsState() {
        show(detailsBox, detailsExpanded);
        detailsLink.setText(ResourcesEngine.getString(detailsExpanded ? "plan.details.hide" : "plan.details.show"));
    }

    private String targetText(WorkItemExecution item) {
        Plan shown = targetsPlan();
        return shown == null || !PlanViewModel.showsTarget(item) ? "" : PlanViewModel.targetText(shown.projectionOf(item));
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

    /**
     * The button of the mode (spec execution-mode §5): "Prepare the plan" (plan), "Prepare and copy" (auto: the copy
     * starts once prepared), "Copy as files are listed" (streaming: no plan, see {@link #startStreaming}).
     */
    @FXML
    protected void onPrepareClick() {
        if (loading || busy() || !model.canPrepare()) {
            return;
        }
        if (model.executionMode() == ExecutionMode.STREAMING) {
            startStreaming();
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
            refresh(); // Stop can now stop it
        }
    }

    private void onPrepareRefused(Object op, Object hold, Throwable failure) {
        release(hold); // prepare() has returned (thrown)
        if (op == operation) {
            preparingPlan = null;
            execution = null; // a refused streaming run
            model.reset();
        }
        refresh();
        PopinUtil.showError(asException(failure));
    }

    /**
     * The streaming run ({@code "execution": "streaming"}, spec execution-mode §5): the engine runs the pipeline
     * directly, each file processed as soon as it is listed; the watcher feeds the table as for a copy, Pause and
     * Stop act on it. No plan: no resume point to change, no target shown. A refused run (invalid pipeline, engine
     * busy) brings the view back to "not prepared".
     */
    private void startStreaming() {
        Object op = new Object();
        operation = op;
        Object hold = hold();
        plan = null;
        preparingPlan = null;
        model.startStreaming();
        CompletableFuture<Execution> future = new CompletableFuture<>();
        execution = future;
        refresh();
        try {
            CopybotMainUi.executor.submit(() -> {
                Execution run;
                try {
                    run = CopybotMainUi.ENGINE.run(pipelinePath, state -> onState(op, state));
                } catch (Throwable t) { // missing or invalid pipeline file, engine busy or closed, an Error
                    future.completeExceptionally(t);
                    Platform.runLater(() -> onPrepareRefused(op, hold, t));
                    return;
                }
                future.complete(run);
                awaitThenRelease(op, hold, future, run);
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
            onPrepareRefused(op, hold, e); // the application is closing
        }
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
            // the index is in resume order, the table may be sorted otherwise: scroll to that row
            itemsTable.scrollTo(rows.get(Math.min(PlanViewModel.resumeScrollIndex(rows, RESUME_CONTEXT_ROWS), rows.size() - 1)));
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
                awaitThenRelease(op, hold, future, run);
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
            onExecuteRefused(op, hold, future, toRun, override, e); // the application is closing
        }
    }

    /** On the background thread of a copy or a streaming run: waits for its real end, then frees the view. */
    private void awaitThenRelease(Object op, Object hold, CompletableFuture<Execution> future, Execution run) {
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
    }

    private void onExecuteRefused(Object op, Object hold, CompletableFuture<Execution> future, Plan toRun,
                                  ResumePoint override, Throwable e) {
        release(hold);
        if (op == operation && future == execution) {
            // a fresh model on the same plan: the phase follows the plan's state again (PREPARED, not locked)
            PlanViewModel back = new PlanViewModel();
            back.setExecutionMode(model.executionMode());
            back.setAutoExecute(model.isAutoExecute());
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
        if (model.phase() == Phase.PREPARING) {
            if (preparingPlan != null) {
                // non-blocking: the end of prepare() shows what was listed and analysed so far
                preparingPlan.cancelPreparation();
            }
            return;
        }
        if (model.phase() == Phase.ANALYSING && plan != null) {
            plan.cancelAnalysis(); // non-blocking: the end of analyse() brings the view back to the plan
            return;
        }
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
                model.resumePointFrom(row.getItem()).ifPresent(this::applyResumePoint);
            }
        });
        MenuItem detail = new MenuItem();
        detail.setOnAction(e -> showDetails(PlanViewModel.detailable(selectedRows()), row.getItem()));
        MenuItem analyse = new MenuItem();
        analyse.setOnAction(e -> analyse(model.analysable(selectedRows())));
        MenuItem ignore = new MenuItem();
        ignore.setOnAction(e -> ignoreForThisRun(model.ignorable(selectedRows()), true));
        MenuItem unignore = new MenuItem();
        unignore.setOnAction(e -> ignoreForThisRun(model.unignorable(selectedRows()), false));
        MenuItem exclude = new MenuItem();
        exclude.setOnAction(e -> alwaysIgnore(selectedRows()));
        ContextMenu menu = new ContextMenu(fromHere, detail, analyse, new SeparatorMenuItem(), ignore, unignore, exclude);
        menu.setOnShowing(e -> {
            fromHere.setDisable(row.getItem() == null || model.resumePointFrom(row.getItem()).isEmpty());
            List<WorkItemExecution> selected = selectedRows();
            boolean busy = busy();
            // both apply to the selection, whatever the clicked row
            setCountedItem(detail, "plan.menu.details", PlanViewModel.detailable(selected).size());
            setCountedItem(analyse, "plan.menu.analyse", busy ? 0 : model.analysable(selected).size());
            setCountedItem(ignore, "plan.menu.ignore", busy ? 0 : model.ignorable(selected).size());
            setCountedItem(unignore, "plan.menu.unignore", busy ? 0 : model.unignorable(selected).size());
            setCountedItem(exclude, "plan.menu.exclude", busy ? 0 : model.excludable(selected).size());
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
    private void showDetail(WorkItemExecution item) {
        showDetails(List.of(item), item);
    }

    /**
     * The planned processing of these analysed items, from the clicked one when it is among them: with several, a
     * counter and previous / next buttons go through them.
     */
    private void showDetails(List<WorkItemExecution> items, WorkItemExecution clicked) {
        Plan shown = targetsPlan();
        if (shown == null || items.isEmpty()) {
            return;
        }
        TextArea text = new TextArea();
        text.setEditable(false);
        text.setWrapText(false);
        text.setStyle("-fx-font-family: 'Consolas', 'monospace';");
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(CopybotMainUi.STAGE);
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(950, 560); // long target paths fit without scrolling
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        int[] index = {PlanViewModel.detailStart(items, clicked)};
        Button previous = new Button("◀");
        Button next = new Button("▶");
        Label position = new Label();
        Runnable display = () -> {
            WorkItemExecution item = items.get(index[0]);
            text.setText(shown.detailOf(item).map(detail -> PlanViewModel.detailText(item, detail)).orElse(""));
            dialog.setTitle(ResourcesEngine.getString("plan.detail.title", item.getWorkItem().getNameDisplay()));
            position.setText(ResourcesEngine.getString("plan.detail.position", index[0] + 1, items.size()));
            previous.setDisable(index[0] == 0);
            next.setDisable(index[0] == items.size() - 1);
        };
        previous.setOnAction(e -> {
            index[0]--;
            display.run();
        });
        next.setOnAction(e -> {
            index[0]++;
            display.run();
        });
        display.run();
        if (items.size() > 1) {
            HBox navigation = new HBox(8.0, previous, position, next);
            navigation.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            VBox content = new VBox(8.0, navigation, text);
            VBox.setVgrow(text, javafx.scene.layout.Priority.ALWAYS);
            dialog.getDialogPane().setContent(content);
        } else {
            dialog.getDialogPane().setContent(text);
        }
        dialog.show();
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
        if (plan == null || busy() || items.isEmpty()) {
            return;
        }
        try {
            if (ignore) {
                plan.ignore(items, model.override());
            } else {
                plan.unignore(items, model.override());
            }
            model.update(plan.getState(), plan.getOrderedItems());
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
        List<Path> files = model.excludable(rows);
        if (files.isEmpty() || busy()) {
            return;
        }
        try {
            PipelineDocument document = PipelineDocument.load(pipelinePath);
            PipelineDocument.Exclusion exclusion = document.exclude(files);
            if (!exclusion.added().isEmpty()) {
                if (!confirmExclusion(document, exclusion)) {
                    return;
                }
                document.save(pipelinePath);
            } else {
                informExclusion(exclusion);
            }
            List<WorkItemExecution> excluded = rows.stream()
                    .filter(r -> r.getListedPath().filter(p -> !exclusion.uncovered().contains(p)).isPresent())
                    .toList();
            List<WorkItemExecution> toIgnore = model.ignorable(excluded);
            if (plan != null && !toIgnore.isEmpty()) {
                plan.ignore(toIgnore, model.override());
                model.update(plan.getState(), plan.getOrderedItems());
            }
        } catch (IOException | RuntimeException e) { // unreadable pipeline, failed save: the file is left as it was
            PopinUtil.showError(e);
        }
        refresh();
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
        List<WorkItemExecution> toAnalyse = plan.toAnalyse();
        if (!toAnalyse.isEmpty()) {
            startAnalysis(toAnalyse);
        }
    }

    /**
     * The files the resume point selected again although the preparation skipped them at the listing are analysed
     * in the background, like a preparation (spec deferred-analysis §2): their targets fill in, Copy waits for the
     * end, Stop ends it early (the files left are then analysed by the copy). The resume point cannot be changed
     * meanwhile: stop the analysis first.
     */
    private void startAnalysis(List<WorkItemExecution> toAnalyse) {
        if (engineHeld) {
            return; // not expected: the plan is prepared and idle; the copy analyses them anyway
        }
        Object op = operation;
        Object hold = hold();
        Plan analysed = plan;
        model.startAnalysing(toAnalyse);
        refresh();
        try {
            CopybotMainUi.executor.submit(() -> {
                Throwable failure = null;
                try {
                    CopybotMainUi.ENGINE.analyse(analysed); // the preparation's watcher reports its progress
                } catch (Throwable t) { // refused (engine busy or closed), or an unexpected failure
                    failure = t;
                }
                Throwable refused = failure;
                Platform.runLater(() -> onAnalysed(op, hold, analysed, refused));
            });
        } catch (RejectedExecutionException e) {
            onAnalysed(op, hold, analysed, e); // the application is closing
        }
    }

    /** analyse() has returned: the plan is PREPARED again, the view follows it. */
    private void onAnalysed(Object op, Object hold, Plan analysed, Throwable failure) {
        release(hold);
        if (op == operation && plan == analysed) {
            model.update(analysed.getState(), analysed.getOrderedItems());
        }
        refresh();
        WorkItemExecution single = detailAfterAnalysis;
        detailAfterAnalysis = null;
        if (failure == null && single != null && op == operation && plan == analysed && single.getProjection() != null) {
            showDetail(single); // one file asked for: what it was analysed for
        }
        if (failure != null) {
            PopinUtil.showError(asException(failure));
        }
    }

    /** The file "Analyse" was asked for alone: its planned processing opens once analysed. */
    private WorkItemExecution detailAfterAnalysis;

    /** "Analyse" on skipped files not analysed: they stay skipped, their planned processing becomes known. */
    private void analyse(List<WorkItemExecution> items) {
        if (plan == null || items.isEmpty() || busy()) {
            return;
        }
        plan.requestAnalysis(items);
        List<WorkItemExecution> toAnalyse = plan.toAnalyse();
        if (!toAnalyse.isEmpty()) {
            detailAfterAnalysis = items.size() == 1 ? items.getFirst() : null;
            startAnalysis(toAnalyse);
        }
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
        prepareButton.setText(model.prepareLabel());
        prepareButton.setDisable(loading || busy || !model.canPrepare());

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
        stopButton.setDisable(!model.canStop() || phase == Phase.PREPARING && preparingPlan == null);

        boolean preparing = phase == Phase.PREPARING || phase == Phase.ANALYSING; // the same bar
        show(progressBox, preparing || model.isExecutionActive() || phase == Phase.FINISHED);
        progressBar.setProgress(preparing ? model.prepareFraction() : model.executionFraction());
        progressLabel.setText(model.progressText());
        statusLine.setText(model.statusLine());

        List<String> warnings = model.warnings(); // the steps' configuration warnings, then the resume ones
        warningBanner.setText(String.join("\n", warnings));
        show(warningBanner, !warnings.isEmpty());
        Optional<String> resume = model.resumeText();
        resumeLabel.setText(resume.orElse(""));
        show(resumeBox, resume.isPresent());
        resumeCountLabel.setText(model.resumeCountText().orElse(""));
        show(resumeCountLabel, model.resumeCountText().isPresent());
        Optional<String> conflicts = model.conflictText();
        conflictLabel.setText(conflicts.orElse(""));
        show(conflictLabel, conflicts.isPresent());
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

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
