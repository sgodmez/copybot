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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Decides which prepared items are imported: proposes a resume point according to the mode,
 * applies a point (SKIPPED with a reason / PENDING), and computes the cursor to persist after the run.
 */
public final class ResumeResolver {

    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ResumeMode mode;
    private final DestinationCheck check;
    private final DestinationMatch match;
    private final ResumeStateStore store;
    private final IOutAction out;

    private Optional<ItemKey> previousCursor = Optional.empty();
    private boolean cursorRead;

    /**
     * The files found at the destination by a check of every file, with the path checked: the point
     * {@link ResumePoint.Kind#NOT_AT_DESTINATION} skips them (spec execution-mode §2). Filled by {@link #propose},
     * or file by file by {@link #checkDestination} while streaming (from several threads).
     */
    private final Map<WorkItemExecution, Path> atDestination = new ConcurrentHashMap<>();
    /** While streaming with an out step that cannot tell whether its directory varies: the target directories seen (2 at most). */
    private final Set<Path> seenDirectories = new HashSet<>();
    private final AtomicBoolean fixedDirectoryWarned = new AtomicBoolean();
    /** While streaming: the out step told no target path (mode stateThenDestination), warned once. */
    private final AtomicBoolean noTargetWarned = new AtomicBoolean();
    /** The warnings found while streaming, not yet published ({@link #drainWarnings}). */
    private final Queue<String> streamingWarnings = new ConcurrentLinkedQueue<>();

    /**
     * The dichotomy on the target directories.
     *
     * @param store state file, unused when mode is NONE
     * @param out   the pipeline out action, null when there is none
     */
    public ResumeResolver(ResumeMode mode, ResumeStateStore store, IOutAction out) {
        this(new ResumeContext(mode, store), out);
    }

    /** @param out the pipeline out action, null when there is none */
    public ResumeResolver(ResumeContext context, IOutAction out) {
        this.mode = context.mode();
        this.check = context.check();
        this.match = context.match();
        this.store = context.store();
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
        if (check == DestinationCheck.EVERY_FILE) {
            return false; // every file is analysed at the listing, then checked
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
            Path found = point.kind() == ResumePoint.Kind.NOT_AT_DESTINATION ? atDestination.get(item) : null;
            if (key.isEmpty()) {
                item.setError(CopybotException.ofResource("resume.item.no-date", item.getWorkItem().getNameDisplay()));
            } else if (found != null) {
                item.setSkippedByResumePoint(atDestinationReason(found));
            } else if (point.selects(key.get())) {
                item.setReady();
            } else {
                item.setSkippedByResumePoint(source == ResumeSource.DESTINATION && match == DestinationMatch.FILE
                        ? skipReason(point, "resume.skip.destination-file") // the dichotomy probed the files
                        : skipReason(point, source));
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
        try {
            return check == DestinationCheck.EVERY_FILE ? everyFile(candidates, analyser) : dichotomy(candidates, analyser);
        } catch (NoTarget e) {
            return noTarget(explicit);
        }
    }

    private ResumeProposal dichotomy(List<WorkItemExecution> candidates, Analyser analyser) throws InterruptedException {
        DestinationProbe.Result result = DestinationProbe.probe(
                candidates.stream().map(item -> item.getResumeKey().orElseThrow()).toList(),
                i -> checkedPath(candidates.get(i), analyser), this::atDestination, match == DestinationMatch.DIRECTORY);
        ResumeSource source = result.point().kind() == ResumePoint.Kind.AFTER ? ResumeSource.DESTINATION : ResumeSource.NONE;
        return new ResumeProposal(result.point(), source, result.warning() == null ? List.of() : List.of(result.warning()));
    }

    /**
     * Every file decided by its own target (spec execution-mode §2): the ones found are remembered for
     * {@link #apply}. A fixed target directory selects everything with a warning: declared by the out step, or
     * guessed when it cannot tell (at least two targets, all in the same directory).
     */
    private ResumeProposal everyFile(List<WorkItemExecution> candidates, Analyser analyser) throws InterruptedException {
        Map<WorkItemExecution, Path> checked = new LinkedHashMap<>();
        for (WorkItemExecution item : candidates) {
            checkedPath(item, analyser).ifPresent(path -> checked.put(item, path));
        }
        atDestination.clear();
        if (match == DestinationMatch.DIRECTORY && !checked.isEmpty()) {
            Path first = checked.values().iterator().next();
            boolean fixed = out.targetDirectoryVaries()
                    .map(varies -> !varies)
                    .orElseGet(() -> checked.size() > 1 && checked.values().stream().allMatch(first::equals));
            if (fixed) {
                return everything(List.of(ResourcesEngine.getString("resume.warn.single-directory", first)));
            }
        }
        checked.forEach((item, path) -> {
            if (atDestination(path)) {
                atDestination.put(item, path);
            }
        });
        return atDestination.isEmpty()
                ? everything(List.of())
                : new ResumeProposal(ResumePoint.notAtDestination(), ResumeSource.DESTINATION, List.of());
    }

    /** The out step cannot tell where it writes (no target path at all): the destination cannot be probed. */
    private static final class NoTarget extends RuntimeException {
        NoTarget() {
            super("no target", null, false, false);
        }
    }

    /**
     * What tells whether the item is at the destination: its target directory, or its target file
     * ({@code destinationMatch}).
     *
     * @throws NoTarget the out step resolves no target path
     */
    private Optional<Path> checkedPath(WorkItemExecution item, Analyser analyser) throws InterruptedException {
        if (!analyser.analyse(item)) {
            return Optional.empty();
        }
        Optional<Path> target = target(item);
        if (target == null) {
            throw new NoTarget();
        }
        return target.map(this::checkedPath);
    }

    /**
     * The target of an analysed item, absolute; empty when it cannot be resolved (e.g. no value for a pattern
     * expression: it stays selected and fails or is skipped at the execution, by its own onMissingKey, spec
     * pattern-helper §2); null when the out step resolves no target path at all.
     */
    private Optional<Path> target(WorkItemExecution item) {
        Optional<Path> target;
        try {
            target = out.resolveTarget(item.getWorkItem());
        } catch (CopybotException e) {
            return Optional.empty();
        }
        return target.isEmpty() ? null : Optional.of(target.get().toAbsolutePath().normalize());
    }

    private Path checkedPath(Path target) {
        return match == DestinationMatch.FILE ? target : target.getParent();
    }

    private boolean atDestination(Path checked) {
        return match == DestinationMatch.FILE ? Files.isRegularFile(checked) : Files.isDirectory(checked);
    }

    private static String atDestinationReason(Path checked) {
        return ResourcesEngine.getString("resume.skip.at-destination", checked);
    }

    // ---- streaming (spec execution-mode §3) ----

    /**
     * The resume point of a run that processes each file as soon as it is listed: known before the listing. After
     * the cursor in the modes that start from it when there is one; with a destination-based mode, every file is
     * checked by {@link #checkDestination} once analysed (the dichotomy needs every file listed: a warning says it is
     * not used).
     *
     * @throws CopybotException the state file cannot be understood, or mode destination without target to probe
     */
    public ResumeProposal streamingProposal() {
        if (mode == ResumeMode.NONE) {
            return everything(List.of());
        }
        readCursorOnce();
        if (mode != ResumeMode.DESTINATION && previousCursor.isPresent()) {
            return fromState().orElseThrow();
        }
        if (mode == ResumeMode.STATE) {
            return everything(List.of());
        }
        if (out == null) {
            return noTarget(mode == ResumeMode.DESTINATION);
        }
        List<String> warnings = check == DestinationCheck.DICHOTOMY
                ? List.of(ResourcesEngine.getString("execution.streaming.dichotomy")) : List.of();
        return new ResumeProposal(ResumePoint.notAtDestination(), ResumeSource.DESTINATION, warnings);
    }

    /**
     * While streaming with the point {@link ResumePoint.Kind#NOT_AT_DESTINATION}: whether this analysed item is at
     * the destination, the skip reason when it is. A fixed target directory skips nothing: declared by the out step
     * (with a warning, once), or while it cannot tell, as long as fewer than two target directories were seen (in
     * doubt the file is copied: the conflict policy of the out step avoids duplicates). Thread-safe.
     */
    public Optional<String> checkDestination(WorkItemExecution item) {
        Optional<Path> target = target(item);
        if (target == null) {
            // the out step tells no target path at all, like the preparation: an error in mode destination, the
            // destination ignored (everything selected, one warning) when it is only the fallback of the cursor
            if (mode == ResumeMode.DESTINATION) {
                throw CopybotException.ofResource("resume.error.no-target");
            }
            if (noTargetWarned.compareAndSet(false, true)) {
                streamingWarnings.add(ResourcesEngine.getString("resume.warn.no-target"));
            }
            return Optional.empty();
        }
        if (target.isEmpty()) {
            return Optional.empty(); // no value for a pattern expression: the out step decides (its onMissingKey)
        }
        Path checked = checkedPath(target.get());
        if (match == DestinationMatch.DIRECTORY && !directoryVaries(checked)) {
            return Optional.empty();
        }
        if (!atDestination(checked)) {
            return Optional.empty();
        }
        atDestination.put(item, checked);
        return Optional.of(atDestinationReason(checked));
    }

    private boolean directoryVaries(Path directory) {
        Optional<Boolean> declared = out.targetDirectoryVaries();
        if (declared.isPresent()) {
            if (!declared.get() && fixedDirectoryWarned.compareAndSet(false, true)) {
                streamingWarnings.add(ResourcesEngine.getString("resume.warn.single-directory", directory));
            }
            return declared.get();
        }
        synchronized (seenDirectories) {
            if (seenDirectories.size() < 2) {
                seenDirectories.add(directory);
            }
            return seenDirectories.size() > 1;
        }
    }

    /** The warnings {@link #checkDestination} found since the last call (to publish). */
    public List<String> drainWarnings() {
        List<String> warnings = new ArrayList<>();
        for (String warning = streamingWarnings.poll(); warning != null; warning = streamingWarnings.poll()) {
            warnings.add(warning);
        }
        return warnings;
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
        String resourceKey = switch (source) {
            case STATE -> "resume.skip.state";
            case DESTINATION -> "resume.skip.destination";
            case MANUAL, NONE -> "resume.skip.manual";
        };
        return skipReason(point, resourceKey);
    }

    /** With the message of this resource key (file name, date), or the one of a date alone. */
    private static String skipReason(ResumePoint point, String resourceKey) {
        String date = DISPLAY_DATE.format(point.key().date());
        if (point.key().name().isEmpty()) {
            // a resume point chosen by date only (--from-date): no file name to show
            return ResourcesEngine.getString("resume.skip.manual-date", date);
        }
        return ResourcesEngine.getString(resourceKey, point.key().name(), date);
    }
}
