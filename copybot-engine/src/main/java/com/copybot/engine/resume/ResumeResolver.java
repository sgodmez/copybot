package com.copybot.engine.resume;

import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decides which prepared items are imported: proposes a resume point according to the mode,
 * applies a point (SKIPPED with a reason / PENDING), and computes the cursor to persist after the run.
 */
public final class ResumeResolver {

    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ResumeMode mode;
    private final ResumeStateStore store;
    private final IOutAction out;

    private Optional<ItemKey> previousCursor = Optional.empty();

    /**
     * @param store state file, unused when mode is NONE
     * @param out   the pipeline out action, null when there is none
     */
    public ResumeResolver(ResumeMode mode, ResumeStateStore store, IOutAction out) {
        this.mode = mode;
        this.store = store;
        this.out = out;
    }

    /**
     * Freezes the resume key of every item (see {@link WorkItemExecution#getResumeKey()}), then returns
     * the items with a key sorted by key, followed by the items without key in their original order.
     */
    public static List<WorkItemExecution> order(Collection<WorkItemExecution> items) {
        List<WorkItemExecution> keyed = new ArrayList<>();
        List<WorkItemExecution> keyless = new ArrayList<>();
        for (WorkItemExecution item : items) {
            item.setResumeKey(ItemKey.of(item.getWorkItem()).orElse(null));
            (item.getResumeKey().isPresent() ? keyed : keyless).add(item);
        }
        keyed.sort(Comparator.comparing(item -> item.getResumeKey().orElseThrow()));
        keyed.addAll(keyless);
        return keyed;
    }

    public ResumeProposal propose(List<WorkItemExecution> ordered) {
        if (mode == ResumeMode.NONE) {
            return everything(List.of());
        }
        previousCursor = store.readCursor();
        return switch (mode) {
            case STATE -> fromState().orElseGet(() -> everything(List.of()));
            case DESTINATION -> fromDestination(ordered, true);
            case STATE_THEN_DESTINATION -> fromState().orElseGet(() -> fromDestination(ordered, false));
            case NONE -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * Applies a resume point to the items still undecided (PENDING or SKIPPED); failed items are left
     * alone, items without any date become errors (never silently skipped). Uses the keys frozen by
     * {@link #order(Collection)}.
     */
    public void apply(ResumePoint point, ResumeSource source, List<WorkItemExecution> ordered) {
        for (WorkItemExecution item : ordered) {
            if (item.getStatus() != ItemStatus.PENDING && item.getStatus() != ItemStatus.SKIPPED) {
                continue;
            }
            Optional<ItemKey> key = item.getResumeKey();
            if (key.isEmpty()) {
                item.setError(CopybotException.ofResource("resume.item.no-date", item.getWorkItem().getNameDisplay()));
            } else if (point.selects(key.get())) {
                item.setReady();
            } else {
                item.setSkipped(skipReason(point, source));
            }
        }
    }

    /**
     * The cursor to persist once the run is over: the last item of the longest run of successes among
     * the selected items (in key order), never before the automatic resume point nor the previous cursor.
     *
     * @return empty when there is nothing (new) to write
     */
    public Optional<ItemKey> nextCursor(List<WorkItemExecution> ordered, ResumePoint point, ResumeSource source) {
        ItemKey lastSuccess = null;
        for (WorkItemExecution item : ordered) {
            // the frozen key: a step after the barrier may have replaced the work item
            Optional<ItemKey> key = item.getResumeKey();
            if (key.isEmpty() || !point.selects(key.get())) {
                continue;
            }
            if (item.getStatus() != ItemStatus.DONE || item.hasFailedFork()) {
                break;
            }
            lastSuccess = key.get();
        }
        ItemKey automaticPoint = (source == ResumeSource.STATE || source == ResumeSource.DESTINATION)
                && point.kind() == ResumePoint.Kind.AFTER ? point.key() : null;
        Optional<ItemKey> next = Stream.of(previousCursor.orElse(null), automaticPoint, lastSuccess)
                .filter(k -> k != null)
                .max(Comparator.naturalOrder());
        if (next.isEmpty() || next.equals(previousCursor)) {
            return Optional.empty();
        }
        return next;
    }

    private Optional<ResumeProposal> fromState() {
        return previousCursor.map(cursor -> new ResumeProposal(ResumePoint.after(cursor), ResumeSource.STATE, List.of()));
    }

    private ResumeProposal fromDestination(List<WorkItemExecution> ordered, boolean explicit) {
        if (out == null) {
            return noTarget(explicit);
        }
        List<DestinationProbe.Candidate> candidates = new ArrayList<>();
        for (WorkItemExecution item : ordered) {
            Optional<ItemKey> key = item.getResumeKey();
            if (item.getStatus() == ItemStatus.ERROR || key.isEmpty()) {
                continue;
            }
            Optional<Path> target = out.resolveTarget(item.getWorkItem());
            if (target.isEmpty()) {
                return noTarget(explicit);
            }
            candidates.add(new DestinationProbe.Candidate(key.get(), target.get().toAbsolutePath().normalize().getParent()));
        }
        DestinationProbe.Result result = DestinationProbe.probe(candidates, Files::isDirectory);
        ResumeSource source = result.point().kind() == ResumePoint.Kind.AFTER ? ResumeSource.DESTINATION : ResumeSource.NONE;
        return new ResumeProposal(result.point(), source, result.warning() == null ? List.of() : List.of(result.warning()));
    }

    private ResumeProposal noTarget(boolean explicit) {
        if (explicit) {
            throw CopybotException.ofResource("resume.error.no-target");
        }
        return everything(List.of(ResourcesEngine.getString("resume.warn.no-target")));
    }

    private static ResumeProposal everything(List<String> warnings) {
        return new ResumeProposal(ResumePoint.all(), ResumeSource.NONE, warnings);
    }

    private static String skipReason(ResumePoint point, ResumeSource source) {
        String date = DISPLAY_DATE.format(point.key().date());
        if (point.key().name().isEmpty()) {
            // a resume point chosen by date only (--from-date): no file name to show
            return ResourcesEngine.getString("resume.skip.manual-date", date);
        }
        String resourceKey = switch (source) {
            case STATE -> "resume.skip.state";
            case DESTINATION -> "resume.skip.destination";
            case MANUAL, NONE -> "resume.skip.manual";
        };
        return ResourcesEngine.getString(resourceKey, point.key().name(), date);
    }
}
