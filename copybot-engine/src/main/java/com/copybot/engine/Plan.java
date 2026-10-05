package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A prepared pipeline: what would be imported, and from where, before anything is written. */
public final class Plan {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Plan.class);

    /**
     * What the plan holds for the current item statuses (spec desktop-ui §5): right after the
     * preparation or a {@link #preview}, the selected items are the PENDING ones.
     *
     * @param selected      items to copy, being copied or copied (PENDING, WAITING_RESOURCES, RUNNING, DONE)
     * @param skipped       SKIPPED items
     * @param errors        ERROR items
     * @param selectedBytes total size of the selected items (an item without size counts 0)
     */
    public record Counts(int selected, int skipped, int errors, long selectedBytes) {

        public static Counts of(Collection<WorkItemExecution> items) {
            int selected = 0;
            int skipped = 0;
            int errors = 0;
            long bytes = 0;
            for (WorkItemExecution item : items) {
                switch (item.getStatus()) {
                    case SKIPPED -> skipped++;
                    case ERROR -> errors++;
                    default -> {
                        selected++;
                        Long size = item.getWorkItem().getMetadatas().getSize();
                        bytes += size == null ? 0 : size;
                    }
                }
            }
            return new Counts(selected, skipped, errors, bytes);
        }

        /** True for the statuses counted as selected. */
        public static boolean isSelected(ItemStatus status) {
            return status != ItemStatus.SKIPPED && status != ItemStatus.ERROR;
        }
    }

    private final MainExecutor executor;

    Plan(MainExecutor executor) {
        this.executor = executor;
    }

    MainExecutor getExecutor() {
        return executor;
    }

    public PipelineState getState() {
        return executor.getState();
    }

    /** Why this plan cannot be executed (no input, or neither output nor process step), empty when it can. */
    public Optional<String> executionRefusal() {
        PipelineConfig config = executor.pipelineConfig();
        return config == null ? Optional.empty() : PipelineChecks.executionRefusal(config);
    }

    /** False when the pipeline does nothing with the files: it can be inspected but not executed. */
    public boolean canExecute() {
        return executionRefusal().isEmpty();
    }

    public ResumeProposal getProposal() {
        return executor.getProposal();
    }

    /** Listed items in resume order (items without date last). */
    public List<WorkItemExecution> getOrderedItems() {
        return executor.getOrderedItems();
    }

    /** The counters of the listed items, for their current statuses. */
    public Counts counts() {
        return Counts.of(getOrderedItems());
    }

    /**
     * Where the out step would write what this item becomes after the dry run of the process steps
     * ({@link IOutAction#resolveTarget}), without writing anything (spec pattern-helper §4.3). Callable while
     * the plan is being prepared (any thread): the items analysed so far have their target, the others none.
     */
    public TargetProjection projectionOf(WorkItemExecution item) {
        IOutAction out = executor.findOutAction();
        if (out == null) {
            return TargetProjection.NONE;
        }
        Projection projection = item.getProjection();
        if (projection == null && (executor.isPreparing() || item.isAnalysisDeferred())) { // not analysed (yet)
            return TargetProjection.NONE;
        }
        if (projection == null) { // not prepared (error before the barrier): the item as it is
            projection = new Projection.Projected(List.of(item.getWorkItem()));
        }
        return outcome(out, projection, new ArrayList<>());
    }

    /**
     * The planned processing of this item (desktop UI): the trace of the dry run of the process steps, then
     * where the out step would write each produced item. Empty while the item is not analysed yet, or when it
     * failed before its dry run. Callable from any thread, like {@link #projectionOf}.
     */
    public Optional<ItemDetail> detailOf(WorkItemExecution item) {
        Projection projection = item.getProjection();
        if (projection == null) {
            return Optional.empty();
        }
        IOutAction out = executor.findOutAction();
        List<Path> files = new ArrayList<>();
        TargetProjection outcome = out == null && projection instanceof Projection.Projected
                ? TargetProjection.NONE : outcome(out, projection, files);
        return Optional.of(new ItemDetail(projection.trace(), outcome,
                outcome instanceof TargetProjection.Targets ? files : List.of()));
    }

    /** How the dry run ended; out is only used (non null then) when it projected items, whose files go to files. */
    private static TargetProjection outcome(IOutAction out, Projection projection, List<Path> files) {
        return switch (projection) {
            case Projection.Filtered f -> new TargetProjection.Filtered(f.action());
            case Projection.Unsupported u -> new TargetProjection.Unknown(u.action());
            case Projection.Failed f -> new TargetProjection.Failed(ResourcesEngine.getString("dryrun.failed", f.action(), f.message()));
            case Projection.Projected p -> targets(out, p.items(), files);
        };
    }

    private static TargetProjection targets(IOutAction out, List<WorkItem> items, List<Path> files) {
        List<Path> directories = new ArrayList<>();
        for (WorkItem produced : items) {
            try {
                Optional<Path> target = out.resolveTarget(produced);
                if (target.isEmpty()) {
                    return TargetProjection.NONE;
                }
                // absolute, like the resume probe: a pattern without directory part writes to the current one
                Path file = target.get().toAbsolutePath().normalize();
                files.add(file);
                directories.add(file.getParent());
            } catch (RuntimeException e) {
                // e.g. a pattern variable this item has no value for
                LOG.debug(e, "plan.target.failed", produced.getNameDisplay(), String.valueOf(e));
                return new TargetProjection.Failed(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
        }
        return new TargetProjection.Targets(directories);
    }

    /** Re-applies a manual resume point to the item statuses, without executing anything (dry-run display). */
    public void preview(ResumePoint override) {
        executor.applyOverride(override);
    }

    /**
     * Leaves these items out of the run, whatever the resume point (SKIPPED, "ignored by the user"); a failed
     * item stays as it is. An ignored item counts as passed for the resume cursor: it is not proposed again.
     *
     * @param override the manual resume point in force, null for the proposed one
     * @throws IllegalStateException the plan is not PREPARED
     */
    public void ignore(Collection<WorkItemExecution> items, ResumePoint override) {
        executor.setIgnored(items, true, override);
    }

    /**
     * Brings ignored items back: the resume point in force decides again whether they are copied.
     *
     * @param override the manual resume point in force, null for the proposed one
     * @throws IllegalStateException the plan is not PREPARED
     */
    public void unignore(Collection<WorkItemExecution> items, ResumePoint override) {
        executor.setIgnored(items, false, override);
    }

    /**
     * Resume from this listed file (its name when it was listed), included.
     *
     * @throws CopybotException resume.from-file.not-found, or resume.from-file.no-date for a listed file
     *                          without any date (it has no place in the resume order)
     */
    public ResumePoint fromFile(String fileName) {
        return getOrderedItems().stream()
                .map(WorkItemExecution::getResumeKey)
                .flatMap(Optional::stream)
                .filter(key -> fileName.equals(key.name()))
                .findFirst()
                .map(ResumePoint::from)
                .orElseThrow(() -> getOrderedItems().stream()
                        .anyMatch(item -> item.getResumeKey().isEmpty()
                                && fileName.equals(item.getWorkItem().getNameDisplay()))
                        ? CopybotException.ofResource("resume.from-file.no-date", fileName)
                        : CopybotException.ofResource("resume.from-file.not-found", fileName));
    }

    /** Resume from the start of this day (system time zone), included. */
    public static ResumePoint fromDate(LocalDate date) {
        return ResumePoint.from(new ItemKey(date.atStartOfDay(ZoneId.systemDefault()).toInstant(), ""));
    }
}
