package com.copybot.ui.model;

import com.copybot.engine.ItemDetail;
import com.copybot.engine.Plan;
import com.copybot.engine.Projection;
import com.copybot.engine.TargetProjection;
import com.copybot.engine.pipeline.ExecutionMode;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.RecentPipelines.LastRun;
import com.copybot.utils.FileUtil;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The state of the plan view (spec desktop-ui §2), without JavaFX: the phase and the buttons it allows,
 * the rows and their filter, the counters, the texts. The controller feeds it the engine state (on the
 * JavaFX thread) and renders it. Not thread-safe.
 */
public final class PlanViewModel {

    public enum Phase {
        /** nothing prepared (at opening, after a reload or a preparation refused before it started) */
        NOT_PREPARED,
        PREPARING,
        PREPARED,
        PREPARE_FAILED,
        /** the preparation was stopped: what was listed and analysed so far stays in view, it cannot be copied */
        PREPARE_STOPPED,
        /**
         * a manual resume point selected again files whose analysis was deferred at the listing: they are being
         * analysed (spec deferred-analysis §2), the plan is PREPARED again afterwards
         */
        ANALYSING,
        RUNNING,
        PAUSED,
        /** the execution ended: SUCCESS, ERROR or CANCELLED */
        FINISHED
    }

    /** CONFLICTS: the files to copy whose target already exists (spec conflict-check §5). */
    public enum Filter { ALL, TO_COPY, SKIPPED, ERRORS, CONFLICTS }

    /** The steps of a preparation: listing the source (total unknown), analysing the files, computing the resume point. */
    public enum PrepareStage { LISTING, ANALYSING, RESOLVING }

    /** A progress without known end (the value of JavaFX's {@code ProgressBar.INDETERMINATE_PROGRESS}). */
    public static final double INDETERMINATE = -1;

    /** Files and bytes processed (done, skipped or failed) among those selected when the copy started. */
    public record Progress(int doneFiles, int totalFiles, long doneBytes, long totalBytes) {
        /** 0 to 1, by bytes when the sizes are known, by files otherwise. */
        public double fraction() {
            if (totalBytes > 0) {
                return (double) doneBytes / totalBytes;
            }
            return totalFiles == 0 ? 0 : (double) doneFiles / totalFiles;
        }
    }

    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter RESUME_DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** The sort of the "Date" column: the date shown (then the name), not its text; the files without date last. */
    public static final Comparator<WorkItemExecution> DATE_ORDER = Comparator.comparing(
            PlanViewModel::dateKey, Comparator.nullsLast(Comparator.naturalOrder()));

    /** The sort of the "Size" column: the bytes, not their text; the unknown sizes last. */
    public static final Comparator<WorkItemExecution> SIZE_ORDER = Comparator.comparing(
            (WorkItemExecution item) -> item.getWorkItem().getMetadatas().getSize(),
            Comparator.nullsLast(Comparator.naturalOrder()));

    private Phase phase = Phase.NOT_PREPARED;
    private boolean executing;
    private boolean autoExecute;
    private boolean autoExecuteArmed;
    private boolean finishedReported;
    private Filter filter = Filter.ALL;
    private List<WorkItemExecution> items = List.of();
    private PipelineStatus status;
    /** The source is still being listed (only meaningful while preparing). */
    private boolean listing;
    private List<String> warnings = List.of();
    private ResumeProposal proposal;
    private ResumePoint override;
    private Throwable failure;
    /** Why the prepared plan cannot be executed (the pipeline does nothing with the files), null when it can. */
    private String executionRefusal;
    /**
     * Why the pipeline cannot be prepared (no input step), null when it can; it belongs to the pipeline read,
     * not to a preparation: kept by {@link #reset()} and {@link #startPreparing()}.
     */
    private String preparationRefusal;
    /** The pipeline does nothing with the files: refuses the one-step modes (see {@link #startRefusal()}). */
    private String nothingDoneRefusal;
    private Set<WorkItemExecution> selectedAtStart = Set.of();
    /** The files being analysed in {@link Phase#ANALYSING}, empty otherwise. */
    private List<WorkItemExecution> analysing = List.of();

    /** The "execution" of the pipeline (spec execution-mode §5): what the button does. */
    private ExecutionMode executionMode = ExecutionMode.PLAN;
    /** The current execution is a streaming run, started without plan ({@link #startStreaming()}). */
    private boolean streaming;
    /** The current run failed before anything was done (its preparation: steps, state file). */
    private boolean preparationFailed;

    /** The automatic execution box unchecked, mode plan. */
    public PlanViewModel() {
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    /** The label of the button that starts the run: "Prepare the plan", "Prepare and copy", "Copy as files are listed". */
    public String prepareLabel() {
        return ResourcesEngine.getString(switch (executionMode) {
            case PLAN -> "plan.prepare";
            case AUTO -> "plan.prepare-and-copy";
            case STREAMING -> "plan.stream";
        });
    }

    /** The "automatic execution" box: only in mode plan (auto copies anyway, streaming has no plan), not while copying. */
    public boolean showsAutoExecuteBox() {
        return executionMode == ExecutionMode.PLAN && !isExecutionActive();
    }

    /**
     * A streaming run starts ({@code "execution": "streaming"}): an execution from the start, without plan; the
     * files are counted as they are listed.
     */
    public void startStreaming() {
        clear();
        executing = true;
        streaming = true;
        listing = true; // until the engine says otherwise
        phase = Phase.RUNNING;
    }

    // ---- feeding ----

    /** A preparation starts: empty table, the automatic execution armed if checked. */
    public void startPreparing() {
        clear();
        phase = Phase.PREPARING;
        listing = true; // until the engine says otherwise
        autoExecuteArmed = true;
    }

    /** The prepared plan starts executing: the selected items and bytes become the progress totals. */
    public void startExecuting() {
        executing = true;
        finishedReported = false;
        autoExecuteArmed = false;
        phase = Phase.RUNNING;
        Set<WorkItemExecution> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        items.stream().filter(i -> Plan.Counts.isSelected(i.getStatus())).forEach(selected::add);
        selectedAtStart = selected;
    }

    /**
     * The files a manual resume point selected again are analysed ({@code Plan.toAnalyse}): the view is locked
     * until the plan is PREPARED again. Only from PREPARED.
     */
    public void startAnalysing(List<WorkItemExecution> toAnalyse) {
        analysing = List.copyOf(toAnalyse);
        autoExecuteArmed = false;
        phase = Phase.ANALYSING;
    }

    /** Back to "not prepared" (pipeline reloaded, preparation refused before it started). */
    public void reset() {
        clear();
        phase = Phase.NOT_PREPARED;
    }

    private void clear() {
        executing = false;
        streaming = false;
        preparationFailed = false;
        autoExecuteArmed = false;
        finishedReported = false;
        items = List.of();
        status = null;
        listing = false;
        warnings = List.of();
        proposal = null;
        override = null;
        executionRefusal = null;
        failure = null;
        selectedAtStart = Set.of();
        analysing = List.of();
    }

    /**
     * The latest engine state (watcher notification, end of the preparation, pause...).
     *
     * @param ordered the plan's items in resume order once it is prepared, empty before: the rows are
     *                then in date order ({@link #DATE_ORDER}); items forked during the execution come after the ordered ones
     */
    public void update(PipelineState state, List<WorkItemExecution> ordered) {
        status = state.getStatus();
        listing = state.isListingInProgress();
        proposal = state.getResumeProposal();
        failure = state.getFailure();
        preparationFailed = state.isPreparationFailed();
        List<String> all = new ArrayList<>(state.getWarnings());
        if (proposal != null) {
            all.addAll(proposal.warnings());
        }
        warnings = List.copyOf(all);
        List<WorkItemExecution> listed = List.copyOf(state.getWorkItems());
        if (ordered.isEmpty()) {
            // the date is known at the listing: no need to wait for the resume order (the same, by date then name)
            items = listed.stream().sorted(DATE_ORDER).toList();
        } else {
            Set<WorkItemExecution> known = Collections.newSetFromMap(new IdentityHashMap<>());
            known.addAll(ordered);
            List<WorkItemExecution> rows = new ArrayList<>(ordered);
            listed.stream().filter(i -> !known.contains(i)).forEach(rows::add);
            items = List.copyOf(rows);
        }
        if (status == null) {
            return;
        }
        if (executing) {
            phase = switch (status) {
                case PAUSED -> Phase.PAUSED;
                // a streaming run failing before the listing (steps, state file): told like a preparation
                case ERROR -> streaming && preparationFailed ? Phase.PREPARE_FAILED : Phase.FINISHED;
                case SUCCESS, CANCELLED -> Phase.FINISHED;
                default -> Phase.RUNNING;
            };
        } else if (!analysing.isEmpty() && (status == PipelineStatus.RUNNING || status == PipelineStatus.PAUSED)) {
            phase = Phase.ANALYSING;
        } else {
            analysing = List.of(); // the analysis is over: the plan is PREPARED again
            phase = switch (status) {
                case PREPARED -> Phase.PREPARED;
                case ERROR -> Phase.PREPARE_FAILED;
                case CANCELLED -> Phase.PREPARE_STOPPED;
                default -> Phase.PREPARING;
            };
        }
    }

    /** A manual resume point, already applied with {@code Plan.preview}: shown on the resume line and executed. */
    public void setOverride(ResumePoint override) {
        this.override = override;
    }

    public ResumePoint override() {
        return override;
    }

    public void setFilter(Filter filter) {
        this.filter = filter;
    }

    public Filter filter() {
        return filter;
    }

    /** The "automatic execution" box of mode plan: a one-shot toggle of the view, never stored (spec execution-mode §1). */
    public void setAutoExecute(boolean autoExecute) {
        this.autoExecute = autoExecute;
    }

    public boolean isAutoExecute() {
        return autoExecute;
    }

    /**
     * True once per preparation, when the plan just became ready, the box is checked (or the mode is auto) and
     * there is something to copy: the controller then starts the copy.
     */
    public boolean consumeAutoExecute() {
        if (autoExecuteArmed && (autoExecute || executionMode == ExecutionMode.AUTO) && phase == Phase.PREPARED
                && counts().selected() > 0) {
            autoExecuteArmed = false;
            return true;
        }
        if (phase != Phase.PREPARING) {
            autoExecuteArmed = false;
        }
        return false;
    }

    /** The summary of the execution, once, when it has just finished: for the recent pipelines. */
    public Optional<LastRun> consumeFinishedRun(Instant now) {
        if (phase != Phase.FINISHED || finishedReported) {
            return Optional.empty();
        }
        finishedReported = true;
        Plan.Counts counts = counts();
        return Optional.of(new LastRun(now, status, copied(), counts.skipped(), counts.errors()));
    }

    private int copied() {
        return (int) items.stream().filter(i -> i.getStatus() == ItemStatus.DONE).count();
    }

    // ---- state ----

    public Phase phase() {
        return phase;
    }

    /** A preparation, an analysis or an execution holds the engine. */
    public boolean isActive() {
        return phase == Phase.PREPARING || phase == Phase.ANALYSING || phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    public boolean isExecutionActive() {
        return phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    /** "← Pipelines", "Edit…" and "Prepare the plan": not while the engine is busy. */
    public boolean canGoBack() {
        return !isActive();
    }

    public boolean canPrepare() {
        return !isActive() && startRefusal() == null;
    }

    /**
     * Why the button that starts the run stays disabled: no input step; or, for the one-step modes (their button
     * copies), a pipeline that does nothing with the files. Null when it can start.
     */
    private String startRefusal() {
        if (preparationRefusal != null) {
            return preparationRefusal;
        }
        return executionMode != ExecutionMode.PLAN ? nothingDoneRefusal : null;
    }

    /** What the pipeline read says when it does nothing with the files (no output nor process step); set on each read. */
    public void setNothingDoneRefusal(Optional<String> refusal) {
        this.nothingDoneRefusal = refusal.orElse(null);
    }

    public boolean canCopy() {
        return phase == Phase.PREPARED && counts().selected() > 0 && executionRefusal == null;
    }

    /** What the pipeline read says about its preparation (no input step); set again each time it is read. */
    public void setPreparationRefusal(Optional<String> refusal) {
        this.preparationRefusal = refusal.orElse(null);
    }

    /** What the engine says about the prepared plan ({@code Plan.executionRefusal}); forgotten by the next preparation. */
    public void setExecutionRefusal(Optional<String> refusal) {
        this.executionRefusal = refusal.orElse(null);
    }

    /**
     * The warning shown beside the buttons when Prepare or Copy stays disabled because of the pipeline or the
     * plan: no input step, the pipeline does nothing with the files, no file to copy. Empty otherwise (a running
     * operation is told by the status line).
     */
    public Optional<String> warning() {
        if (startRefusal() != null) {
            return Optional.of(startRefusal());
        }
        if (phase != Phase.PREPARED) {
            return Optional.empty();
        }
        if (executionRefusal != null) {
            return Optional.of(executionRefusal);
        }
        if (counts().selected() == 0) {
            return Optional.of(ResourcesEngine.getString("plan.warning.nothing-to-copy"));
        }
        return Optional.empty();
    }

    public boolean canPause() {
        return phase == Phase.RUNNING;
    }

    public boolean canResume() {
        return phase == Phase.PAUSED;
    }

    /** "Stop": during a preparation, an execution or an analysis of the files selected again. */
    public boolean canStop() {
        return phase == Phase.PREPARING || isExecutionActive() || phase == Phase.ANALYSING;
    }

    /** "change…" and "Resume from here": a prepared plan, before its execution. */
    public boolean canChangeResumePoint() {
        return phase == Phase.PREPARED && proposal != null;
    }

    /** The resume point "Resume from here" gives for this row: from its key, included. */
    public Optional<ResumePoint> resumePointFrom(WorkItemExecution item) {
        return canChangeResumePoint() ? item.getResumeKey().map(ResumePoint::from) : Optional.empty();
    }

    /** Among these rows, those with a planned processing: the analysed ones (any phase). */
    public static List<WorkItemExecution> detailable(List<WorkItemExecution> rows) {
        return rows.stream().filter(i -> i.getProjection() != null).toList();
    }

    /** Where the planned processing of these rows opens: on the clicked row when it is one of them, else the first. */
    public static int detailStart(List<WorkItemExecution> detailable, WorkItemExecution clicked) {
        return Math.max(0, detailable.indexOf(clicked));
    }

    /** Among these rows, those "Analyse" analyses: a prepared plan, skipped rows not analysed (skipped at the listing). */
    public List<WorkItemExecution> analysable(List<WorkItemExecution> rows) {
        if (phase != Phase.PREPARED) {
            return List.of();
        }
        return rows.stream().filter(i -> i.isAnalysisDeferred() && i.getStatus() == ItemStatus.SKIPPED).toList();
    }

    // ---- files ignored by the user (context menu of the rows) ----

    /** Among these rows, those "Ignore for this copy" leaves out: a prepared plan, rows to copy or skipped by the resume point. */
    public List<WorkItemExecution> ignorable(List<WorkItemExecution> rows) {
        if (phase != Phase.PREPARED) {
            return List.of();
        }
        return rows.stream().filter(i -> !i.isIgnored()
                && (i.getStatus() == ItemStatus.PENDING || i.getStatus() == ItemStatus.SKIPPED)).toList();
    }

    /** Among these rows, those "Include again" brings back: a prepared plan, rows ignored for this run. */
    public List<WorkItemExecution> unignorable(List<WorkItemExecution> rows) {
        return phase == Phase.PREPARED ? rows.stream().filter(WorkItemExecution::isIgnored).toList() : List.of();
    }

    /**
     * The files "Always ignore" excludes from the pipeline for these rows: their listed local files, once each.
     * Before and after an execution, never while the engine is busy.
     */
    public List<Path> excludable(List<WorkItemExecution> rows) {
        if (isActive()) {
            return List.of();
        }
        return rows.stream().map(WorkItemExecution::getListedPath).flatMap(Optional::stream).distinct().toList();
    }

    public List<WorkItemExecution> items() {
        return items;
    }

    /** Once the copy started, "To copy" is what is left: not copied yet (the copied ones are DONE). */
    private static boolean isLeftToCopy(ItemStatus status) {
        return status == ItemStatus.PENDING || status == ItemStatus.WAITING_RESOURCES || status == ItemStatus.RUNNING;
    }

    /**
     * The row to scroll to (shown at the top) so that the resume junction is in view: the last {@code context}
     * rows not selected (already imported, skipped or in error), then the first selected one; the top without
     * resume, the last rows when nothing is selected. The rows are in resume order.
     */
    public static int resumeScrollIndex(List<WorkItemExecution> rows, int context) {
        int junction = rows.size();
        for (int i = 0; i < rows.size(); i++) {
            if (Plan.Counts.isSelected(rows.get(i).getStatus())) {
                junction = i;
                break;
            }
        }
        return Math.max(0, junction - context);
    }

    /** The rows of the current filter. */
    public List<WorkItemExecution> visibleItems() {
        return items.stream().filter(i -> switch (filter) {
            case ALL -> true;
            case TO_COPY -> executing ? isLeftToCopy(i.getStatus()) : Plan.Counts.isSelected(i.getStatus());
            case SKIPPED -> i.getStatus() == ItemStatus.SKIPPED;
            case ERRORS -> i.getStatus() == ItemStatus.ERROR;
            case CONFLICTS -> isConflict(i);
        }).toList();
    }

    /** A file left to copy whose target already exists, as the plan checked it. */
    private static boolean isConflict(WorkItemExecution item) {
        TargetCheck check = item.getTargetCheck();
        return check != null && check.exists() && isLeftToCopy(item.getStatus());
    }

    /**
     * "⚠ 42 files already exist at the destination (40 same size, 2 different size)", or identical / different after a
     * full check (spec conflict-check §5): the files to copy only, until the copy starts; empty without any.
     */
    public Optional<String> conflictText() {
        if (executing || !(phase == Phase.PREPARING || phase == Phase.PREPARED || phase == Phase.ANALYSING
                || phase == Phase.PREPARE_STOPPED)) {
            return Optional.empty();
        }
        int same = 0;
        int different = 0;
        boolean full = false;
        for (WorkItemExecution item : items) {
            if (isConflict(item)) {
                Kind kind = item.getTargetCheck().kind();
                full |= kind == Kind.IDENTICAL || kind == Kind.DIFFERENT;
                if (kind == Kind.SAME_SIZE || kind == Kind.IDENTICAL) {
                    same++;
                } else {
                    different++;
                }
            }
        }
        if (same + different == 0) {
            return Optional.empty();
        }
        return Optional.of(ResourcesEngine.getString(full ? "plan.conflicts.full" : "plan.conflicts.quick",
                same + different, same, different));
    }

    public Plan.Counts counts() {
        return Plan.Counts.of(items);
    }

    /**
     * Among the files selected when the copy started; while streaming (nothing selected at the start), among the
     * files listed so far that the resume point does not skip.
     */
    public Progress progress() {
        int done = 0;
        long doneBytes = 0;
        long totalBytes = 0;
        Collection<WorkItemExecution> counted = streaming
                ? items.stream().filter(i -> !i.isSkippedByResumePoint()).toList()
                : selectedAtStart;
        for (WorkItemExecution item : counted) {
            long size = size(item);
            totalBytes += size;
            ItemStatus s = item.getStatus();
            if (s == ItemStatus.DONE || s == ItemStatus.SKIPPED || s == ItemStatus.ERROR) {
                done++;
                doneBytes += size;
            }
        }
        return new Progress(done, counted.size(), doneBytes, totalBytes);
    }

    /** The bar of the execution: {@link #progress()}, {@link #INDETERMINATE} while a streaming run still lists (the total grows). */
    public double executionFraction() {
        return streaming && listing ? INDETERMINATE : progress().fraction();
    }

    /** Where the preparation is (only meaningful in PREPARING). */
    public PrepareStage prepareStage() {
        if (listing) {
            return PrepareStage.LISTING;
        }
        return preparedCount() < items.size() ? PrepareStage.ANALYSING : PrepareStage.RESOLVING;
    }

    /**
     * The bar of the preparation: the analysed share of the listed files, {@link #INDETERMINATE} otherwise; in
     * {@link Phase#ANALYSING}, the analysed share of the files selected again.
     */
    public double prepareFraction() {
        if (phase == Phase.ANALYSING) {
            return (double) analysedCount() / analysing.size();
        }
        return prepareStage() == PrepareStage.ANALYSING ? (double) preparedCount() / items.size() : INDETERMINATE;
    }

    /** Among the files being analysed, those no longer deferred (analysed, or failed). */
    private int analysedCount() {
        return (int) analysing.stream().filter(i -> !i.isAnalysisDeferred()).count();
    }

    private int preparedCount() {
        return (int) items.stream().filter(WorkItemExecution::isPrepared).count();
    }

    /** Among the listed files, those analysed (or failed): prepared, their analysis not deferred. */
    private int analysedOfListed() {
        return (int) items.stream().filter(i -> i.isPrepared() && !i.isAnalysisDeferred()).count();
    }

    private static long size(WorkItemExecution item) {
        Long size = item.getWorkItem().getMetadatas().getSize();
        return size == null ? 0 : size;
    }

    /** The configuration warnings of the steps, then the resume ones (spec desktop-ui §2). */
    public List<String> warnings() {
        return warnings;
    }

    // ---- texts ----

    /** A size for the display, in the current locale and language ("292,97 Ko"). */
    private static String displaySize(long size, int decimals) {
        return FileUtil.toAutoUnitSize(size, decimals, Locale.getDefault());
    }

    /** The target column (spec pattern-helper §4.3): the first directory and "(+N)", or why there is none. */
    public static String targetText(TargetProjection projection) {
        return switch (projection) {
            case TargetProjection.Targets t -> t.directories().isEmpty() ? ""
                    : t.directories().getFirst() + (t.directories().size() > 1
                    ? " " + ResourcesEngine.getString("plan.target.more", t.directories().size() - 1) : "");
            case TargetProjection.Filtered f -> ResourcesEngine.getString("dryrun.filtered", f.action());
            case TargetProjection.Unknown u -> ResourcesEngine.getString("dryrun.unsupported", u.action());
            case TargetProjection.Failed f -> f.message();
            case TargetProjection.None n -> "";
        };
    }

    /** Every directory, one per line, when there are several; null otherwise. */
    public static String targetTooltip(TargetProjection projection) {
        return projection instanceof TargetProjection.Targets t && t.directories().size() > 1
                ? t.directories().stream().map(Path::toString).collect(Collectors.joining("\n")) : null;
    }

    /**
     * The planned processing of an item ("Planned processing…" of the row menu): the source, its status, each
     * process step and what it produced, then every file the out step would write, or why the dry run stopped.
     */
    public static String detailText(WorkItemExecution item, ItemDetail detail) {
        WorkItem source = item.getWorkItem();
        List<String> lines = new ArrayList<>();
        lines.add("📂 " + source.getNameDisplay());
        lines.add("   " + Stream.of(source.getSourceLocationDisplay(), sizeText(item), dateText(item))
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.joining(" · ")));
        // the full status: for a file the resume point skips, its reason (the column only says "Skipped")
        Optional<String> reason = statusTooltip(item);
        lines.add(ResourcesEngine.getString("plan.detail.status", reason.isPresent()
                ? ResourcesEngine.getString("item.status.SKIPPED", reason.get()) : statusText(item)));
        lines.add("");
        for (Projection.Step step : detail.steps()) {
            lines.add("⚙ " + step.action() + " → " + String.join(", ", step.produced()));
        }
        switch (detail.outcome()) {
            case TargetProjection.Targets t -> {
                for (int i = 0; i < detail.targets().size(); i++) {
                    lines.add((i == 0 ? "💾 " : "   ") + detail.targets().get(i));
                }
            }
            case TargetProjection.Filtered f ->
                    lines.add("⚙ " + f.action() + " → " + ResourcesEngine.getString("plan.detail.filtered"));
            case TargetProjection.Unknown u ->
                    lines.add("⚙ " + u.action() + " → " + ResourcesEngine.getString("plan.detail.unsupported"));
            case TargetProjection.Failed f -> lines.add("⚠ " + f.message());
            case TargetProjection.None n -> lines.add("💾 " + ResourcesEngine.getString("plan.detail.no-target"));
        }
        return String.join("\n", lines);
    }

    /** The "Size" column: the item's size for the display, "?" when unknown. */
    public static String sizeText(WorkItemExecution item) {
        Long size = item.getWorkItem().getMetadatas().getSize();
        return size == null ? "?" : displaySize(size, 2);
    }

    /** "Copy N files (X GB)". */
    public String copyLabel() {
        Plan.Counts counts = counts();
        return ResourcesEngine.getString("plan.copy", counts.selected(), displaySize(counts.selectedBytes(), 1));
    }

    /**
     * "12 / 40 files, 1.2 GB / 3.5 GB"; while preparing, its step: "Listing the source… 120 files",
     * "Analysing the files… 40 / 120", "Computing the resume point…".
     */
    public String progressText() {
        if (phase == Phase.PREPARING) {
            return switch (prepareStage()) {
                case LISTING -> ResourcesEngine.getString("plan.preparing", items.size());
                case ANALYSING -> ResourcesEngine.getString("plan.analysing", preparedCount(), items.size());
                case RESOLVING -> ResourcesEngine.getString("plan.resolving");
            };
        }
        if (phase == Phase.ANALYSING) {
            return ResourcesEngine.getString("plan.analysing-again", analysedCount(), analysing.size());
        }
        Progress p = progress();
        return ResourcesEngine.getString("plan.progress", p.doneFiles(), p.totalFiles(),
                displaySize(p.doneBytes(), 1), displaySize(p.totalBytes(), 1));
    }

    /** The status line under the table: preparation failure, end of the run; empty otherwise (the preparation is told by the bar). */
    public String statusLine() {
        return switch (phase) {
            case PREPARE_FAILED -> ResourcesEngine.getString("plan.prepare-failed", errorText(failure));
            case PREPARE_STOPPED -> ResourcesEngine.getString("plan.prepare-stopped", items.size(), analysedOfListed());
            case FINISHED -> ResourcesEngine.getString("plan.finished", pipelineStatusText(status),
                    copied(), counts().skipped(), counts().errors());
            default -> "";
        };
    }

    /** "Resume: after DSC_4821 (28/09 17:42) [cursor]", empty before a preparation. */
    public Optional<String> resumeText() {
        if (proposal == null || phase == Phase.NOT_PREPARED || phase == Phase.PREPARING || phase == Phase.PREPARE_FAILED
                || phase == Phase.PREPARE_STOPPED) {
            return Optional.empty();
        }
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        String sourceText = label("plan.resume.source." + source.name(), source.name());
        ItemKey key = point.key();
        if (point.kind() == ResumePoint.Kind.NOT_AT_DESTINATION) {
            return Optional.of(ResourcesEngine.getString("plan.resume.missing", sourceText));
        }
        if (point.kind() == ResumePoint.Kind.ALL || key == null) {
            return Optional.of(ResourcesEngine.getString("plan.resume.all", sourceText));
        }
        if (key.name().isEmpty()) {
            return Optional.of(ResourcesEngine.getString("plan.resume.from-date",
                    DAY.format(key.date().atZone(ZoneId.systemDefault())), sourceText));
        }
        String date = RESUME_DATE.format(key.date().atZone(ZoneId.systemDefault()));
        String kind = point.kind() == ResumePoint.Kind.AFTER ? "plan.resume.after" : "plan.resume.from";
        return Optional.of(ResourcesEngine.getString(kind, key.name(), date, sourceText));
    }

    /**
     * The number of files the resume point in force skips, for the resume banner: "already imported" for a detected
     * point, "before the chosen point" for a manual one; empty when it skips none.
     */
    public Optional<String> resumeCountText() {
        if (resumeText().isEmpty()) {
            return Optional.empty();
        }
        long count = items.stream().filter(WorkItemExecution::isSkippedByResumePoint).count();
        if (count == 0) {
            return Optional.empty();
        }
        String key = override != null ? "plan.resume.count.before" : "plan.resume.count.imported";
        return Optional.of(ResourcesEngine.getString(key, count));
    }

    /** Only the files to copy (or copied, or in error) show their target: a skipped file is not written. */
    public static boolean showsTarget(WorkItemExecution item) {
        return item.getStatus() != ItemStatus.SKIPPED;
    }

    /**
     * The reason of a file the resume point skips: its status only says "Skipped" (the header tells why); for a file
     * to copy whose target exists, what the copy will do with it (spec conflict-check §5).
     */
    public static Optional<String> statusTooltip(WorkItemExecution item) {
        if (item.isSkippedByResumePoint()) {
            return Optional.ofNullable(item.getSkipReason());
        }
        TargetCheck check = item.getTargetCheck();
        return item.getStatus() == ItemStatus.PENDING && check != null && check.exists()
                ? Optional.ofNullable(check.message()) : Optional.empty();
    }

    /** "To copy", "Waiting — resources", "Copying 42 %", "Copied", "Skipped — reason", "Error — message" (spec desktop-ui §2). */
    public static String statusText(WorkItemExecution item) {
        return switch (item.getStatus()) {
            case PENDING -> {
                String pending = ResourcesEngine.getString("item.status.PENDING");
                TargetCheck check = item.getTargetCheck();
                yield check != null && check.exists()
                        ? pending + " " + ResourcesEngine.getString("item.status.PENDING.exists." + check.kind().name())
                        : pending;
            }
            case WAITING_RESOURCES -> ResourcesEngine.getString("item.status.WAITING_RESOURCES",
                    String.join(", ", item.getWaitingFor()));
            case RUNNING -> {
                WorkStatus ws = item.getWorkStatus();
                yield ws != null && ws.actionPercent() >= 0
                        ? ResourcesEngine.getString("item.status.RUNNING.percent", String.valueOf(ws.actionPercent()))
                        : ResourcesEngine.getString("item.status.RUNNING");
            }
            case DONE -> ResourcesEngine.getString("item.status.DONE");
            case SKIPPED -> item.isSkippedByResumePoint() || item.getSkipReason() == null || item.getSkipReason().isBlank()
                    ? ResourcesEngine.getString("item.status.SKIPPED.no-reason")
                    : ResourcesEngine.getString("item.status.SKIPPED", item.getSkipReason());
            case ERROR -> ResourcesEngine.getString("item.status.ERROR", errorText(item.getError()));
        };
    }

    /** The message of a failure, its class simple name when it has none ("" without failure). */
    static String errorText(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** The "Date" column: the resume key date, the capture or modification date before the preparation. */
    public static String dateText(WorkItemExecution item) {
        ItemKey key = dateKey(item);
        return key == null ? "" : ITEM_DATE.format(key.date().atZone(ZoneId.systemDefault()));
    }

    /** What the "Date" column shows, null without date. */
    private static ItemKey dateKey(WorkItemExecution item) {
        return item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem())).orElse(null);
    }

    /** The label of a terminal pipeline status ("Success", "Failed", "Stopped"); the raw name for another one. */
    public static String pipelineStatusText(PipelineStatus status) {
        if (status == null) {
            return "";
        }
        return label("pipeline.status." + status.name(), status.name());
    }

    /** "28/09/2026 17:42 — Success: 120 copied, 3 skipped, 1 error(s)", or "Never run". */
    public static String lastRunText(LastRun run) {
        if (run == null) {
            return ResourcesEngine.getString("recent.never-run");
        }
        return ResourcesEngine.getString("recent.last-run", ITEM_DATE.format(run.at().atZone(ZoneId.systemDefault())),
                pipelineStatusText(run.status()), run.copied(), run.skipped(), run.errors());
    }

    /** The look of a badge of the last run. */
    public enum BadgeKind { SUCCESS, ERROR, NEUTRAL }

    /** A small label of the last run line: its text and its look. */
    public record Badge(String text, BadgeKind kind) {
    }

    /** The date of a last run, "dd/MM/yyyy HH:mm". */
    public static String lastRunDate(LastRun run) {
        return ITEM_DATE.format(run.at().atZone(ZoneId.systemDefault()));
    }

    /**
     * The badges of the last run line: the status, then the copied, skipped and error counts (the error
     * badge is red only when there is an error); none when the pipeline never ran.
     */
    public static List<Badge> lastRunBadges(LastRun run) {
        if (run == null) {
            return List.of();
        }
        String status = pipelineStatusText(run.status());
        Badge statusBadge = switch (run.status()) {
            case SUCCESS -> new Badge("✓ " + status, BadgeKind.SUCCESS);
            case ERROR -> new Badge("✗ " + status, BadgeKind.ERROR);
            default -> new Badge(status, BadgeKind.NEUTRAL);
        };
        return List.of(statusBadge,
                new Badge(ResourcesEngine.getString("plan.badge.copied", run.copied()), BadgeKind.NEUTRAL),
                new Badge(ResourcesEngine.getString("plan.badge.skipped", run.skipped()), BadgeKind.NEUTRAL),
                new Badge(ResourcesEngine.getString(run.errors() > 1 ? "plan.badge.errors" : "plan.badge.error", run.errors()),
                        run.errors() > 0 ? BadgeKind.ERROR : BadgeKind.NEUTRAL));
    }

    /** The resume mode of the header: "no resume", "cursor, then destination"...; the raw mode when unknown. */
    public static String resumeModeText(String mode) {
        // no resume block and mode "none" behave the same: one label
        return mode == null ? label("plan.resume-mode.none", "none") : label("plan.resume-mode." + mode, mode);
    }

    /** The translation of a key built from a value, or that raw value when the bundle has no such key. */
    private static String label(String key, String raw) {
        String text = ResourcesEngine.getString(key);
        return text.startsWith("%") ? raw : text; // "%key": no such key
    }
}
