package com.copybot.engine.resume;

import com.copybot.config.SecretReferences;
import com.copybot.engine.Redaction;
import com.copybot.engine.pipeline.ItemStatus;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.resources.ResourcesEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final SecretReferences.Redactor redactor;

    private Optional<ItemKey> previousCursor = Optional.empty();
    private boolean cursorRead;
    /** The destination point follows a missing target ({@link DestinationProbe.Result#toCheck()}): no cursor from it. */
    private boolean pointToCheck;

    /**
     * The files found at the destination by a check of every file, with the path checked: the point
     * {@link ResumePoint.Kind#NOT_AT_DESTINATION} skips them (spec execution-mode §2). Filled by {@link #propose},
     * or file by file by {@link #checkDestination} while streaming (from several threads).
     */
    private final Map<WorkItemExecution, Path> atDestination = new ConcurrentHashMap<>();
    /** {@link #propose} decided every file by its own target ({@link #checkedEveryFile}). */
    private volatile boolean everyFileChecked;
    /** {@link #propose} found the target directory of the pipeline fixed: its files are not probed ({@link #probed}). */
    private volatile boolean pipelineDirectoryFixed;
    /** While streaming with an out step that cannot tell whether its directory varies: the target directories seen (2 at most). */
    private final Set<Path> seenDirectories = new HashSet<>();
    private final AtomicBoolean fixedDirectoryWarned = new AtomicBoolean();
    /** While streaming: the fixed directories of own out patterns already warned about (spec own-out-pattern §2). */
    private final Set<Path> fixedOwnDirectoriesWarned = ConcurrentHashMap.newKeySet();
    /** While streaming: the out step told no target path (mode stateThenDestination), warned once. */
    private final AtomicBoolean noTargetWarned = new AtomicBoolean();
    /** The warnings found while streaming, not yet published ({@link #drainWarnings}). */
    private final Queue<String> streamingWarnings = new ConcurrentLinkedQueue<>();

    /**
     * The search from the end, on the target directories.
     *
     * @param store state file, unused when mode is NONE
     * @param out   the pipeline out action, null when there is none
     */
    public ResumeResolver(ResumeMode mode, ResumeStateStore store, IOutAction out) {
        this(new ResumeContext(mode, store), out);
    }

    /** @param out the pipeline out action, null when there is none */
    public ResumeResolver(ResumeContext context, IOutAction out) {
        this(context, out, SecretReferences.Redactor.NONE);
    }

    /**
     * @param out      the pipeline out action, null when there is none
     * @param redactor hides the secret values of the out step in the failures of its targets
     */
    public ResumeResolver(ResumeContext context, IOutAction out, SecretReferences.Redactor redactor) {
        this.mode = context.mode();
        this.check = context.check();
        this.match = context.match();
        this.store = context.store();
        this.out = out;
        this.redactor = redactor;
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

        /** Analyses these items before the probe asks them one by one with {@link #analyse} (e.g. in parallel). */
        default void analyseAll(List<WorkItemExecution> items) throws InterruptedException {
            for (WorkItemExecution item : items) {
                analyse(item);
            }
        }
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

    /**
     * Reads the state file cursor without proposing anything: a resume point chosen by the user replaces the
     * proposal, but {@link #nextCursor} still never moves the cursor back (spec manual-point §1). Nothing to read in
     * mode none.
     *
     * @throws CopybotException the state file cannot be understood
     */
    public void readCursor() {
        if (mode != ResumeMode.NONE) {
            readCursorOnce();
        }
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
                        ? skipReason(point, "resume.skip.destination-file") // the search from the end checked the files
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
     * The destination point to check (it follows a missing target) writes nothing: the next run probes the destination
     * again, warned again, instead of resuming from a cursor that would skip for good what is not imported yet.
     *
     * @return empty when there is nothing (new) to write
     */
    public Optional<ItemKey> nextCursor(List<WorkItemExecution> ordered, ResumePoint point, ResumeSource source) {
        if (pointToCheck && source == ResumeSource.DESTINATION) {
            return Optional.empty();
        }
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
            return check == DestinationCheck.EVERY_FILE ? everyFile(candidates, analyser) : lastFound(candidates, analyser);
        } catch (NoTarget e) {
            return noTarget(explicit);
        }
    }

    /**
     * After the last file found at the destination, the files gone through from the end by batches. The single-directory
     * guard compares the files following the pipeline pattern only; a file whose own out pattern has a fixed directory
     * cannot be probed (spec own-out-pattern §2).
     */
    private ResumeProposal lastFound(List<WorkItemExecution> candidates, Analyser analyser) throws InterruptedException {
        DestinationProbe.Targets targets = new DestinationProbe.Targets() {
            @Override
            public Optional<Path> dirOf(int index) throws InterruptedException {
                WorkItemExecution item = candidates.get(index);
                return fixedOwnDirectory(item) ? Optional.empty() : checkedPath(item, analyser);
            }

            @Override
            public void resolve(List<Integer> indices) throws InterruptedException {
                analyser.analyseAll(indices.stream().map(candidates::get).filter(item -> !fixedOwnDirectory(item)).toList());
            }
        };
        DestinationProbe.Result result = DestinationProbe.probe(
                candidates.stream().map(item -> item.getResumeKey().orElseThrow()).toList(),
                targets, this::atDestination, match == DestinationMatch.DIRECTORY,
                index -> ownDirectoryVaries(candidates.get(index)).isEmpty());
        pointToCheck = result.toCheck();
        ResumeSource source = result.point().kind() == ResumePoint.Kind.AFTER ? ResumeSource.DESTINATION : ResumeSource.NONE;
        return new ResumeProposal(result.point(), source, result.warning() == null ? List.of() : List.of(result.warning()));
    }

    /**
     * Every file decided by its own target (spec execution-mode §2): the ones found are remembered for
     * {@link #apply}. A fixed target directory of the pipeline keeps its files selected, with a warning: declared by
     * the out step, or guessed when it cannot tell (at least two targets, all in the same directory). A file with its
     * own out pattern follows the same rule by that pattern: probed on its own target when its directory varies, kept
     * selected with a warning when fixed (spec own-out-pattern §2).
     */
    private ResumeProposal everyFile(List<WorkItemExecution> candidates, Analyser analyser) throws InterruptedException {
        everyFileChecked = false;
        Map<WorkItemExecution, Path> checked = new LinkedHashMap<>();
        for (WorkItemExecution item : candidates) {
            checkedPath(item, analyser).ifPresent(path -> checked.put(item, path));
        }
        atDestination.clear();
        List<Path> shared = checked.entrySet().stream()
                .filter(entry -> ownDirectoryVaries(entry.getKey()).isEmpty()).map(Map.Entry::getValue).toList();
        boolean fixed = false;
        if (match == DestinationMatch.DIRECTORY && !shared.isEmpty()) {
            Path first = shared.getFirst();
            fixed = outDirectoryVaries()
                    .map(varies -> !varies)
                    .orElseGet(() -> shared.size() > 1 && shared.stream().allMatch(first::equals));
        }
        Set<String> warnings = new LinkedHashSet<>();
        if (fixed) {
            warnings.add(ResourcesEngine.getString("resume.warn.single-directory", shared.getFirst()));
        }
        pipelineDirectoryFixed = fixed;
        checked.forEach((item, path) -> {
            if (fixedOwnDirectory(item)) {
                warnings.add(ResourcesEngine.getString("resume.warn.single-directory", path));
            }
            if (probed(item) && atDestination(path)) {
                atDestination.put(item, path);
            }
        });
        everyFileChecked = true;
        return atDestination.isEmpty()
                ? everything(List.copyOf(warnings))
                : new ResumeProposal(ResumePoint.notAtDestination(), ResumeSource.DESTINATION, List.copyOf(warnings));
    }

    /**
     * Checks again at the destination these items analysed again (their time zone corrected: spec
     * timezone-correction §3), for {@link #apply} with the point {@link ResumePoint.Kind#NOT_AT_DESTINATION}: an item
     * whose new target is there is remembered with it, the others are forgotten. Like {@link #everyFile}, a file whose
     * directory is fixed (the pipeline's, or its own out pattern's) is not probed: it stays selected. No warning,
     * nothing else changes.
     */
    public void recheck(Collection<WorkItemExecution> items) {
        for (WorkItemExecution item : items) {
            if (!probed(item)) {
                atDestination.remove(item);
                continue;
            }
            Optional<Path> target;
            try {
                target = target(item);
            } catch (RuntimeException e) {
                target = Optional.empty(); // the out step fails on it: unknown target, it stays selected
            }
            Path checked = target == null || target.isEmpty() ? null : checkedPath(target.get());
            if (checked != null && atDestination(checked)) {
                atDestination.put(item, checked);
            } else {
                atDestination.remove(item);
            }
        }
    }

    /**
     * True when the proposal comes from checking every file at the destination by its own target ({@code
     * destinationCheck} every file), even when none was found there (the proposal is then
     * {@link ResumePoint#all()}): a file whose target is found there later is skipped too, like at the preparation.
     */
    public boolean checkedEveryFile() {
        return everyFileChecked;
    }

    /**
     * With every file checked ({@link #everyFile}): whether this file is probed at the destination by its own target.
     * Not when its own out pattern has a fixed directory, nor when it follows the pipeline pattern whose directory is
     * fixed (spec own-out-pattern §2).
     */
    private boolean probed(WorkItemExecution item) {
        if (fixedOwnDirectory(item)) {
            return false;
        }
        return ownDirectoryVaries(item).isPresent() || !pipelineDirectoryFixed;
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
     * pattern-helper §2); null when the out step resolves no target path at all. Another failure is thrown without
     * the secret values of the out step.
     */
    private Optional<Path> target(WorkItemExecution item) {
        Optional<Path> target;
        try {
            target = out.resolveTarget(item.getWorkItem());
        } catch (CopybotException e) {
            return Optional.empty();
        } catch (RuntimeException | Error e) {
            throw Redaction.rethrow(redactor, e);
        }
        return target.isEmpty() ? null : Optional.of(target.get().toAbsolutePath().normalize());
    }

    /** {@link IOutAction#targetDirectoryVaries} of the out step, a failure thrown without its secret values. */
    private Optional<Boolean> outDirectoryVaries() {
        try {
            return out.targetDirectoryVaries();
        } catch (RuntimeException | Error e) {
            throw Redaction.rethrow(redactor, e);
        }
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
     * checked by {@link #checkDestination} once analysed (the search from the end needs every file listed: a warning says it is
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
        List<String> warnings = check == DestinationCheck.LAST_FOUND
                ? List.of(ResourcesEngine.getString("execution.streaming.last-found")) : List.of();
        return new ResumeProposal(ResumePoint.notAtDestination(), ResumeSource.DESTINATION, warnings);
    }

    /**
     * While streaming with the point {@link ResumePoint.Kind#NOT_AT_DESTINATION}: whether this analysed item is at
     * the destination, the skip reason when it is. A fixed target directory skips nothing: declared by the out step
     * (with a warning, once), or while it cannot tell, as long as fewer than two target directories were seen (in
     * doubt the file is copied: the conflict policy of the out step avoids duplicates). A file with its own out
     * pattern is checked on its own target when that pattern's directory varies, else skips nothing (a warning per
     * directory, once) (spec own-out-pattern §2). Thread-safe.
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
        if (fixedOwnDirectory(item)) {
            if (fixedOwnDirectoriesWarned.add(checked)) {
                streamingWarnings.add(ResourcesEngine.getString("resume.warn.single-directory", checked));
            }
            return Optional.empty();
        }
        if (match == DestinationMatch.DIRECTORY && ownDirectoryVaries(item).isEmpty() && !directoryVaries(checked)) {
            return Optional.empty();
        }
        if (!atDestination(checked)) {
            return Optional.empty();
        }
        atDestination.put(item, checked);
        return Optional.of(atDestinationReason(checked));
    }

    /**
     * Whether the directory of the item's own out pattern varies, empty when it follows the pipeline pattern (spec
     * own-out-pattern §2). A pattern that cannot be parsed counts as varying: its target cannot be resolved anyway.
     */
    private static Optional<Boolean> ownDirectoryVaries(WorkItemExecution item) {
        return item.getWorkItem().getOutPattern().map(own -> {
            try {
                return OutPattern.parse(own).directoryVaries();
            } catch (CopybotException e) {
                return true;
            }
        });
    }

    /** The item's own out pattern has a fixed directory, and the directory tells whether a file is imported. */
    private boolean fixedOwnDirectory(WorkItemExecution item) {
        return match == DestinationMatch.DIRECTORY && ownDirectoryVaries(item).map(varies -> !varies).orElse(false);
    }

    private boolean directoryVaries(Path directory) {
        Optional<Boolean> declared = outDirectoryVaries();
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

    /** A date as the messages show it. */
    public static String displayDate(Instant date) {
        return DISPLAY_DATE.format(date);
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
        String date = displayDate(point.key().date());
        if (point.key().name().isEmpty()) {
            // a resume point chosen by date only (--from-date): no file name to show
            return ResourcesEngine.getString("resume.skip.manual-date", date);
        }
        return ResourcesEngine.getString(resourceKey, point.key().name(), date);
    }
}
