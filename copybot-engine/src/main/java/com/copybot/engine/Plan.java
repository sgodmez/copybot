package com.copybot.engine;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.IOutAction;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
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
     * The (absolute) directory the out step would write this item to ({@link IOutAction#resolveTarget}), without
     * writing anything; empty without out step, when the action cannot tell or fails to resolve it.
     */
    public Optional<Path> targetOf(WorkItemExecution item) {
        IOutAction out = executor.findOutAction();
        if (out == null) {
            return Optional.empty();
        }
        try {
            // absolute, like the resume probe: a pattern without directory part writes to the current one
            return out.resolveTarget(item.getWorkItem()).map(target -> target.toAbsolutePath().normalize().getParent());
        } catch (RuntimeException e) {
            // e.g. a pattern variable this item has no value for, in a plugin
            LOG.debug(e, "plan.target.failed", item.getWorkItem().getNameDisplay(), String.valueOf(e));
            return Optional.empty();
        }
    }

    /** Re-applies a manual resume point to the item statuses, without executing anything (dry-run display). */
    public void preview(ResumePoint override) {
        executor.applyOverride(override);
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
