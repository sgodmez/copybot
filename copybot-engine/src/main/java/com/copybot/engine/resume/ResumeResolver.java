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
    private boolean cursorRead;

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

    /**
     * Analyses an item on demand, for the destination probe to resolve its target.
     */
    @FunctionalInterface
    public interface Analyser {
        /** @return false when the item cannot be probed (its analysis failed) */
        boolean analyse(WorkItemExecution item) throws InterruptedException;
    }

    /** The items are already analysed: those in error cannot be probed. */
    private static final Analyser ANALYSED = item -> item.getStatus() != ItemStatus.ERROR;

    /** For items already analysed. */
    public ResumeProposal propose(List<WorkItemExecution> ordered) {
        try {
            return propose(ordered, ANALYSED);
        } catch (InterruptedException e) {
            throw new IllegalStateException("unreachable: nothing is analysed", e);
        }
    }

    /**
     * @param analyser analyses the items the destination probe checks, when they are not analysed yet
     */
    public ResumeProposal propose(List<WorkItemExecution> ordered, Analyser analyser) throws InterruptedException {
        if (mode == ResumeMode.NONE) {
            return everything(List.of());
        }
        readCursorOnce();
        return switch (mode) {
            case STATE -> fromState().orElseGet(() -> everything(List.of()));
            case DESTINATION -> fromDestination(ordered, true, analyser);
            case STATE_THEN_DESTINATION -> {
                Optional<ResumeProposal> state = fromState();
                yield state.isPresent() ? state.get() : fromDestination(ordered, false, analyser);
            }
            case NONE -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * The resume point known before the listing: after the state file cursor, in the modes that start from it
     * when there is one. An item it does not select can be skipped as soon as it is listed (its key, the file
     * modification date, comes with the listing): {@link #propose} proposes that same point.
     *
     * @return empty when the point depends on the listed items (no cursor, mode none or destination)
     * @throws CopybotException the state file cannot be understood
     */
    public Optional<ResumePoint> listingPoint() {
        if (mode != ResumeMode.STATE && mode != ResumeMode.STATE_THEN_DESTINATION) {
            return Optional.empty();
        }
        readCursorOnce();
        return previousCursor.map(ResumePoint::after);
    }

    /**
     * True when {@link #propose} will probe the destination (mode destination, or no cursor to start from): the
     * items need no analysis before it, it analyses the ones it checks.
     *
     * @throws CopybotException the state file cannot be understood
     */
    public boolean probesTheDestination() {
        if (out == null) {
            return false; // no target to probe: propose falls back (or fails) as without probe
        }
        if (mode == ResumeMode.DESTINATION) {
            return true;
        }
        if (mode == ResumeMode.STATE_THEN_DESTINATION) {
            readCursorOnce();
            return previousCursor.isEmpty();
        }
        return false;
    }

    private void readCursorOnce() {
        if (!cursorRead) {
            previousCursor = store.readCursor();
            cursorRead = true;
        }
    }

    /**
     * Applies a resume point to the items still undecided (PENDING or SKIPPED); failed items and the items
     * the user ignored are left alone, items without any date become errors (never silently skipped). Uses
     * the keys frozen by {@link #order(Collection)}.
     */
    public void apply(ResumePoint point, ResumeSource source, List<WorkItemExecution> ordered) {
        for (WorkItemExecution item : ordered) {
            if (item.isIgnored() || item.getStatus() != ItemStatus.PENDING && item.getStatus() != ItemStatus.SKIPPED) {
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
     * A selected item succeeded when it ended DONE, or SKIPPED during the execution (the out step found it
     * already at the destination: spec safe-write §2) or by the user (ignored), and none of its forks failed.
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
            if (!succeeded(item)) {
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

    /**
     * For a selected item: SKIPPED can only come from the execution or from the user, the resume point skipped
     * only the others.
     */
    private static boolean succeeded(WorkItemExecution item) {
        ItemStatus status = item.getStatus();
        return (status == ItemStatus.DONE || status == ItemStatus.SKIPPED) && !item.hasFailedFork();
    }

    private Optional<ResumeProposal> fromState() {
        return previousCursor.map(cursor -> new ResumeProposal(ResumePoint.after(cursor), ResumeSource.STATE, List.of()));
    }

    private ResumeProposal fromDestination(List<WorkItemExecution> ordered, boolean explicit, Analyser analyser)
            throws InterruptedException {
        if (out == null) {
            return noTarget(explicit);
        }
        List<WorkItemExecution> candidates = ordered.stream()
                .filter(item -> item.getStatus() != ItemStatus.ERROR && item.getResumeKey().isPresent())
                .toList();
        DestinationProbe.Result result;
        try {
            result = DestinationProbe.probe(candidates.stream().map(item -> item.getResumeKey().orElseThrow()).toList(),
                    i -> targetDir(candidates.get(i), analyser), Files::isDirectory);
        } catch (NoTarget e) {
            return noTarget(explicit);
        }
        ResumeSource source = result.point().kind() == ResumePoint.Kind.AFTER ? ResumeSource.DESTINATION : ResumeSource.NONE;
        return new ResumeProposal(result.point(), source, result.warning() == null ? List.of() : List.of(result.warning()));
    }

    /** The out step cannot tell where it writes (no target path at all): the destination cannot be probed. */
    private static final class NoTarget extends RuntimeException {
        NoTarget() {
            super("no target", null, false, false);
        }
    }

    /** @throws NoTarget the out step resolves no target path */
    private Optional<Path> targetDir(WorkItemExecution item, Analyser analyser) throws InterruptedException {
        if (!analyser.analyse(item)) {
            return Optional.empty();
        }
        Optional<Path> target;
        try {
            target = out.resolveTarget(item.getWorkItem());
        } catch (CopybotException e) {
            // e.g. no value for a pattern expression: this item cannot be probed, it stays selected and
            // fails or is skipped at the execution, by its own onMissingKey (spec pattern-helper §2)
            return Optional.empty();
        }
        if (target.isEmpty()) {
            throw new NoTarget();
        }
        return Optional.of(target.get().toAbsolutePath().normalize().getParent());
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

    /** Only for an item the point does not select: never for ALL, which selects everything (hence a key). */
    public static String skipReason(ResumePoint point, ResumeSource source) {
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
