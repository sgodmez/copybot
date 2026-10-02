package com.copybot.ui.model;

import com.copybot.engine.Plan;
import com.copybot.engine.TargetProjection;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.PipelineStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.RecentPipelines.LastRun;
import com.copybot.utils.FileUtil;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The state of the plan view (spec desktop-ui §2), without JavaFX: the phase and the buttons it allows,
 * the rows and their filter, the counters, the texts. The controller feeds it the engine state (on the
 * JavaFX thread) and renders it. Not thread-safe.
 */
public final class PlanViewModel {

    public enum Phase {
        /** nothing prepared (at opening, after a reload or a cancelled preparation) */
        NOT_PREPARED,
        PREPARING,
        PREPARED,
        PREPARE_FAILED,
        RUNNING,
        PAUSED,
        /** the execution ended: SUCCESS, ERROR or CANCELLED */
        FINISHED
    }

    public enum Filter { ALL, TO_COPY, SKIPPED, ERRORS }

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
    private Set<WorkItemExecution> selectedAtStart = Set.of();

    /** @param autoExecute "ui.autoExecute" of the pipeline: copy as soon as the plan is ready */
    public PlanViewModel(boolean autoExecute) {
        this.autoExecute = autoExecute;
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

    /** Back to "not prepared" (pipeline reloaded, preparation refused before it started). */
    public void reset() {
        clear();
        phase = Phase.NOT_PREPARED;
    }

    private void clear() {
        executing = false;
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
    }

    /**
     * The latest engine state (watcher notification, end of the preparation, pause...).
     *
     * @param ordered the plan's items in resume order once it is prepared, empty before: the rows are
     *                then in listing order; items forked during the execution come after the ordered ones
     */
    public void update(PipelineState state, List<WorkItemExecution> ordered) {
        status = state.getStatus();
        listing = state.isListingInProgress();
        proposal = state.getResumeProposal();
        failure = state.getFailure();
        List<String> all = new ArrayList<>(state.getWarnings());
        if (proposal != null) {
            all.addAll(proposal.warnings());
        }
        warnings = List.copyOf(all);
        List<WorkItemExecution> listed = List.copyOf(state.getWorkItems());
        if (ordered.isEmpty()) {
            items = listed;
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
                case SUCCESS, ERROR, CANCELLED -> Phase.FINISHED;
                default -> Phase.RUNNING;
            };
        } else {
            phase = switch (status) {
                case PREPARED -> Phase.PREPARED;
                case ERROR -> Phase.PREPARE_FAILED;
                case CANCELLED -> Phase.NOT_PREPARED;
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

    /** The "automatic execution" box: this session only (spec desktop-ui §2). */
    public void setAutoExecute(boolean autoExecute) {
        this.autoExecute = autoExecute;
    }

    public boolean isAutoExecute() {
        return autoExecute;
    }

    /**
     * True once per preparation, when the plan just became ready, the box is checked and there is
     * something to copy: the controller then starts the copy.
     */
    public boolean consumeAutoExecute() {
        if (autoExecuteArmed && autoExecute && phase == Phase.PREPARED && counts().selected() > 0) {
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

    /** A preparation or an execution holds the engine. */
    public boolean isActive() {
        return phase == Phase.PREPARING || phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    public boolean isExecutionActive() {
        return phase == Phase.RUNNING || phase == Phase.PAUSED;
    }

    /** "← Pipelines", "Edit…" and "Prepare the plan": not while the engine is busy. */
    public boolean canGoBack() {
        return !isActive();
    }

    public boolean canPrepare() {
        return !isActive() && preparationRefusal == null;
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
        if (preparationRefusal != null) {
            return Optional.of(preparationRefusal);
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

    /** "Stop": during an execution only (a preparation is not stopped from the view). */
    public boolean canStop() {
        return isExecutionActive();
    }

    /** "change…" and "Resume from here": a prepared plan, before its execution. */
    public boolean canChangeResumePoint() {
        return phase == Phase.PREPARED && proposal != null;
    }

    /** The resume point "Resume from here" gives for this row: from its key, included. */
    public Optional<ResumePoint> resumePointFrom(WorkItemExecution item) {
        return canChangeResumePoint() ? item.getResumeKey().map(ResumePoint::from) : Optional.empty();
    }

    public List<WorkItemExecution> items() {
        return items;
    }

    /** Once the copy started, "To copy" is what is left: not copied yet (the copied ones are DONE). */
    private static boolean isLeftToCopy(ItemStatus status) {
        return status == ItemStatus.PENDING || status == ItemStatus.WAITING_RESOURCES || status == ItemStatus.RUNNING;
    }

    /** The rows of the current filter. */
    public List<WorkItemExecution> visibleItems() {
        return items.stream().filter(i -> switch (filter) {
            case ALL -> true;
            case TO_COPY -> executing ? isLeftToCopy(i.getStatus()) : Plan.Counts.isSelected(i.getStatus());
            case SKIPPED -> i.getStatus() == ItemStatus.SKIPPED;
            case ERRORS -> i.getStatus() == ItemStatus.ERROR;
        }).toList();
    }

    public Plan.Counts counts() {
        return Plan.Counts.of(items);
    }

    public Progress progress() {
        int done = 0;
        long doneBytes = 0;
        long totalBytes = 0;
        for (WorkItemExecution item : selectedAtStart) {
            long size = size(item);
            totalBytes += size;
            ItemStatus s = item.getStatus();
            if (s == ItemStatus.DONE || s == ItemStatus.SKIPPED || s == ItemStatus.ERROR) {
                done++;
                doneBytes += size;
            }
        }
        return new Progress(done, selectedAtStart.size(), doneBytes, totalBytes);
    }

    /** Where the preparation is (only meaningful in PREPARING). */
    public PrepareStage prepareStage() {
        if (listing) {
            return PrepareStage.LISTING;
        }
        return preparedCount() < items.size() ? PrepareStage.ANALYSING : PrepareStage.RESOLVING;
    }

    /** The bar of the preparation: the analysed share of the listed files, {@link #INDETERMINATE} otherwise. */
    public double prepareFraction() {
        return prepareStage() == PrepareStage.ANALYSING ? (double) preparedCount() / items.size() : INDETERMINATE;
    }

    private int preparedCount() {
        return (int) items.stream().filter(WorkItemExecution::isPrepared).count();
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
        Progress p = progress();
        return ResourcesEngine.getString("plan.progress", p.doneFiles(), p.totalFiles(),
                displaySize(p.doneBytes(), 1), displaySize(p.totalBytes(), 1));
    }

    /** The status line under the table: preparation failure, end of the run; empty otherwise (the preparation is told by the bar). */
    public String statusLine() {
        return switch (phase) {
            case PREPARE_FAILED -> ResourcesEngine.getString("plan.prepare-failed", errorText(failure));
            case FINISHED -> ResourcesEngine.getString("plan.finished", pipelineStatusText(status),
                    copied(), counts().skipped(), counts().errors());
            default -> "";
        };
    }

    /** "Resume: after DSC_4821 (28/09 17:42) [cursor]", empty before a preparation. */
    public Optional<String> resumeText() {
        if (proposal == null || phase == Phase.NOT_PREPARED || phase == Phase.PREPARING || phase == Phase.PREPARE_FAILED) {
            return Optional.empty();
        }
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        String sourceText = label("plan.resume.source." + source.name(), source.name());
        ItemKey key = point.key();
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

    /** "To copy", "Waiting — resources", "Copying 42 %", "Copied", "Skipped — reason", "Error — message" (spec desktop-ui §2). */
    public static String statusText(WorkItemExecution item) {
        return switch (item.getStatus()) {
            case PENDING -> ResourcesEngine.getString("item.status.PENDING");
            case WAITING_RESOURCES -> ResourcesEngine.getString("item.status.WAITING_RESOURCES",
                    String.join(", ", item.getWaitingFor()));
            case RUNNING -> {
                WorkStatus ws = item.getWorkStatus();
                yield ws != null && ws.actionPercent() >= 0
                        ? ResourcesEngine.getString("item.status.RUNNING.percent", String.valueOf(ws.actionPercent()))
                        : ResourcesEngine.getString("item.status.RUNNING");
            }
            case DONE -> ResourcesEngine.getString("item.status.DONE");
            case SKIPPED -> item.getSkipReason() == null || item.getSkipReason().isBlank()
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
        return item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem()))
                .map(key -> ITEM_DATE.format(key.date().atZone(ZoneId.systemDefault())))
                .orElse("");
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

    /** The resume mode of the header: "no resume", "cursor, then destination"...; the raw mode when unknown. */
    public static String resumeModeText(String mode) {
        return mode == null
                ? label("plan.resume-mode.absent", "")
                : label("plan.resume-mode." + mode, mode);
    }

    /** The translation of a key built from a value, or that raw value when the bundle has no such key. */
    private static String label(String key, String raw) {
        String text = ResourcesEngine.getString(key);
        return text.startsWith("%") ? raw : text; // "%key": no such key
    }
}
