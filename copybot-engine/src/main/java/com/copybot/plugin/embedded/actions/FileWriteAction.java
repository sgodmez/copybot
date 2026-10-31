package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import com.copybot.resources.ResourcesEngine;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes the items under outPattern (spec safe-write): an existing target is compared then skipped,
 * renamed, overwritten or refused; the copy goes through a temporary file (or not), is hashed and
 * verified; the source is deleted afterwards when asked.
 * <p>
 * One SafeFileWriter per action instance, shared by every item of the step. Two concurrent items of the
 * step with the same base target are serialized by a per-target lock held around the resolution and the
 * write; the writer's in-flight guard still refuses two writes of one final target.
 */
public class FileWriteAction extends AbstractActionWithConfig<FileWriteConfig> implements IOutAction {
    /** Resolutions of one item when its target keeps being created by another process. */
    private static final int TARGET_ATTEMPTS = 3;

    /** The normalized absolute base targets (from outPattern) being resolved and written right now. */
    private final ConcurrentHashMap<Path, TargetLock> targetLocks = new ConcurrentHashMap<>();

    /** The parsed own patterns of the items, by text (few distinct ones). */
    private final ConcurrentHashMap<String, OutPattern> ownPatterns = new ConcurrentHashMap<>();

    private volatile FileWriteSettings settings;
    private volatile ConflictResolver conflicts;
    private volatile SafeFileWriter writer;
    private volatile GroupWriter group;

    private static final System.Logger LOGGER = System.getLogger(FileWriteAction.class.getName());

    /** Whether the "deleteSource ignored" warning was logged in this run (reset by {@link #beforeAll}). */
    private final AtomicBoolean keptWarned = new AtomicBoolean();

    @Override
    public void beforeAll() {
        keptWarned.set(false);
    }

    @Override
    protected Class<FileWriteConfig> getConfigClass() {
        return FileWriteConfig.class;
    }

    /** @throws CopybotException when the configuration is invalid */
    @Override
    public void loadConfig(JsonElement config) {
        super.loadConfig(config);
        FileWriteSettings loaded = FileWriteSettings.of(getConfig());
        conflicts = new ConflictResolver(loaded);
        writer = new SafeFileWriter(loaded); // knows deleteSource: forces the data to disk before closing
        group = new GroupWriter(loaded, conflicts, writer);
        settings = loaded;
    }

    @Override
    public List<String> configWarnings() {
        FileWriteSettings current = settings;
        return current == null ? List.of() : current.warnings();
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(Path.of((item == null ? settings.outPattern() : pattern(item)).staticPrefix()));
    }

    /** The item's own pattern when it has one (spec own-out-pattern §1), else the configured outPattern. */
    private OutPattern pattern(WorkItem item) {
        return item.getOutPattern().map(text -> ownPatterns.computeIfAbsent(text, OutPattern::parse))
                .orElseGet(() -> settings.outPattern());
    }

    /** The COPY companions are written next to the main file (see {@link GroupWriter}). */
    @Override
    public boolean writesCompanions() {
        return true;
    }

    @Override
    public Optional<String> defaultOutPattern() {
        return Optional.of(getConfig().outPattern());
    }

    /**
     * Items resolving to the same target (e.g. 100CANON/IMG_0001.JPG and 101CANON/IMG_0001.JPG) are
     * serialized: the second one waits for the first to be written, then finds "exists, different" and
     * goes to "IMG_0001 (1).JPG". A target created meanwhile by another process is resolved again, at most
     * {@value #TARGET_ATTEMPTS} times; a temporary file name already taken is never retried.
     *
     * @throws CopybotException the item fails: conflict "error", failed verification, source not deleted,
     *                          interrupted while waiting for its target (cancel), or any I/O failure (a
     *                          temporary file name already taken, an atomic replace not supported...),
     *                          whose message ends with the cause's
     */
    @Override
    public WriteResult write(WorkItem workItem, WriteContext context) {
        if (workItem.isSourceKept() && settings.deleteSource() && keptWarned.compareAndSet(false, true)) {
            // an out step has no run warning channel: logged, once per run and step
            LOGGER.log(System.Logger.Level.WARNING,
                    "file.write: deleteSource is ignored for a secondary output, the source is kept");
        }
        OutPattern.Resolution resolution = resolution(workItem);
        if (!resolution.complete()) {
            switch (settings.onMissingKey()) {
                case ERROR -> throw missingKey(workItem, resolution);
                case SKIP -> {
                    return WriteResult.skipped(null, ResourcesEngine.getString("write.skip.missing-key",
                            String.join(", ", resolution.missing())));
                }
                case LITERAL -> { } // the expression stays as written (historical behaviour)
            }
        }
        Path target = Path.of(resolution.text());
        Path key = target.toAbsolutePath().normalize();
        TargetLock lock;
        try {
            lock = lockTarget(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CopybotException.ofResource(e, "write.error.io-detail", target, detail(e));
        }
        try {
            return writeResolvingAgain(workItem, target, context);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "write.error.io-detail", target, detail(e));
        } finally {
            unlockTarget(key, lock);
        }
    }

    /** The message of the cause, or its class when it has none. */
    static String detail(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    /** Visible for tests. */
    SafeFileWriter writer() {
        return writer;
    }

    /** Visible for tests: the number of targets locked or waited for right now. */
    int lockedTargets() {
        return targetLocks.size();
    }

    /** A lock per base target, removed once nobody holds or waits for it (the map does not grow). */
    private static final class TargetLock {
        final ReentrantLock lock = new ReentrantLock();
        int users; // only read and written inside the map's compute functions, atomic per key
    }

    private TargetLock lockTarget(Path key) throws InterruptedException {
        TargetLock targetLock = targetLocks.compute(key, (k, existing) -> {
            TargetLock l = existing != null ? existing : new TargetLock();
            l.users++;
            return l;
        });
        try {
            targetLock.lock.lockInterruptibly(); // a cancel interrupts the wait
            return targetLock;
        } catch (InterruptedException e) {
            releaseTarget(key);
            throw e;
        }
    }

    private void unlockTarget(Path key, TargetLock targetLock) {
        targetLock.lock.unlock();
        releaseTarget(key);
    }

    private void releaseTarget(Path key) {
        targetLocks.computeIfPresent(key, (k, l) -> --l.users == 0 ? null : l);
    }

    /** The resolved target was taken (by another process) between its resolution and its write. */
    static final class TargetTaken extends IOException {
        TargetTaken(FileAlreadyExistsException cause) {
            super(cause);
        }
    }

    private WriteResult writeResolvingAgain(WorkItem workItem, Path target, WriteContext context) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return doWrite(workItem, target, context);
            } catch (TargetTaken e) {
                if (attempt >= TARGET_ATTEMPTS) {
                    throw (FileAlreadyExistsException) e.getCause();
                }
            }
        }
    }

    /** @throws CopybotException write.pattern.missing-key, unless onMissingKey is "literal" */
    @Override
    public Optional<Path> resolveTarget(WorkItem workItem) {
        OutPattern.Resolution resolution = resolution(workItem);
        if (!resolution.complete() && settings.onMissingKey() != FileWriteSettings.MissingKey.LITERAL) {
            throw missingKey(workItem, resolution);
        }
        return Optional.of(Path.of(resolution.text()));
    }

    /**
     * What the write would find at the target (spec conflict-check §3), nothing written: quick reads the target's
     * attributes once and compares the sizes; full makes the comparison and applies the policies of the write.
     * A target it cannot resolve (missing key) or reach is UNKNOWN.
     */
    @Override
    public TargetCheck checkTarget(WorkItem workItem, boolean compareContent) {
        OutPattern.Resolution resolution = resolution(workItem);
        if (!resolution.complete() && settings.onMissingKey() != FileWriteSettings.MissingKey.LITERAL) {
            return TargetCheck.UNKNOWN; // the target column already tells why
        }
        Path target = Path.of(resolution.text());
        try {
            List<Companion> copies = GroupWriter.copies(workItem);
            if (!copies.isEmpty()) {
                return group.check(workItem, copies, target, compareContent);
            }
            BasicFileAttributes existing;
            try {
                existing = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return TargetCheck.free();
            }
            if (ConflictResolver.isSource(workItem, target)) {
                return new TargetCheck(Kind.IDENTICAL, target, ResourcesEngine.getString("write.check.same-file"), true);
            }
            return compareContent ? fullCheck(workItem, target, existing) : quickCheck(settings, workItem, target, existing);
        } catch (IOException | RuntimeException e) {
            return TargetCheck.UNKNOWN; // the copy meets it again, and reports it
        }
    }

    static TargetCheck quickCheck(FileWriteSettings current, WorkItem workItem, Path target, BasicFileAttributes existing)
            throws IOException {
        if (!existing.isDirectory() && existing.size() == FileComparison.sourceSize(workItem)) {
            return new TargetCheck(Kind.SAME_SIZE, target, ResourcesEngine.getString("write.check.same-size",
                    target, current.compare().jsonName(), policyText(current.ifIdentical()), policyText(current.ifDifferent())));
        }
        return new TargetCheck(Kind.DIFFERENT_SIZE, target,
                ResourcesEngine.getString("write.check.different-size", target, policyText(current.ifDifferent())));
    }

    private TargetCheck fullCheck(WorkItem workItem, Path target, BasicFileAttributes existing) throws IOException {
        FileWriteSettings current = settings;
        // as ConflictResolver: a directory is never identical, and is never read
        boolean identical = !existing.isDirectory() && FileComparison.identical(workItem, target, current.compare());
        FileWriteSettings.Policy policy = identical ? current.ifIdentical() : current.ifDifferent();
        String outcome = policyText(policy);
        boolean skipped = policy == FileWriteSettings.Policy.SKIP;
        if (policy == FileWriteSettings.Policy.RENAME) {
            try {
                ConflictResolver.Decision decision = conflicts.resolve(workItem, target); // reads only
                skipped = decision.isSkip(); // a renamed candidate identical to the item
                outcome = decision.isSkip() ? decision.skipReason()
                        : ResourcesEngine.getString("write.check.renamed", decision.target().getFileName());
            } catch (CopybotException e) {
                outcome = e.getMessage(); // a later candidate with the policy "error"
            }
        }
        return new TargetCheck(identical ? Kind.IDENTICAL : Kind.DIFFERENT, target,
                ResourcesEngine.getString(identical ? "write.check.identical" : "write.check.different", target, outcome),
                skipped);
    }

    static String policyText(FileWriteSettings.Policy policy) {
        return ResourcesEngine.getString("write.check.policy." + policy.name());
    }

    /** From outPattern: a key before its last separator (spec execution-mode §2). */
    @Override
    public Optional<Boolean> targetDirectoryVaries() {
        return Optional.of(settings.outPattern().directoryVaries());
    }

    /**
     * The target of the item, or why there is none (spec pattern-helper §2).
     *
     * @return the resolution; with ERROR or SKIP, check {@link OutPattern.Resolution#complete()} first
     */
    private OutPattern.Resolution resolution(WorkItem workItem) {
        return pattern(workItem).resolve(workItem.getMetadatas().display());
    }

    private CopybotException missingKey(WorkItem workItem, OutPattern.Resolution resolution) {
        // URL items have no file name
        String name = workItem.getNameDisplay() != null ? workItem.getNameDisplay() : workItem.getSourceLocationDisplay();
        return CopybotException.ofResource("write.pattern.missing-key", String.join(", ", resolution.missing()), name);
    }

    private WriteResult doWrite(WorkItem workItem, Path target, WriteContext context) throws IOException {
        List<Companion> copies = GroupWriter.copies(workItem);
        if (!copies.isEmpty()) {
            return group.write(workItem, copies, target, context);
        }
        ItemProgress progress = context.progress();
        progress.report(new WorkStatus("Copy file " + workItem.getSourceLocationDisplay(), -1));

        ConflictResolver.Decision decision = conflicts.resolve(workItem, target);
        if (decision.isSkip()) {
            // only a full comparison proves the destination holds the whole content (spec safe-write §5);
            // a destination that is the source itself is the only copy: never deleted
            if (!decision.sameFile() && decision.identical() && settings.compare() == Compare.FULL_HASH) {
                deleteSource(settings, workItem, decision.target());
            }
            return WriteResult.skipped(decision.target(), decision.skipReason());
        }

        SafeFileWriter.Written written;
        try {
            written = writer.write(workItem, decision.target(), decision.replaceExisting(), context.runId(), progress::percent,
                    context::directoryCreated);
        } catch (FileAlreadyExistsException e) {
            if (isFile(e, decision.target())) {
                throw new TargetTaken(e); // resolved again; a temporary file name collision is not
            }
            throw e;
        }
        workItem.getMetadatas().raw().put(WorkItemMetadata.SHA256, written.sha256());
        context.fileWritten(decision.target(), written.sha256(), written.size());
        deleteSource(settings, workItem, decision.target());
        return WriteResult.written(decision.target());
    }

    /**
     * deleteSource: once written and verified, or already at the destination and compared with fullHash.
     * A local source is deleted; another one through its source deleter (e.g. an entry of an archive); a URL
     * source without deleter (or its temporary copy) is kept, without error.
     *
     * @throws CopybotException write.delete-source.failed
     */
    static void deleteSource(FileWriteSettings settings, WorkItem workItem, Path target) {
        if (!settings.deleteSource() || workItem.isDeleted() || workItem.isSourceKept()) {
            return; // nothing asked, already gone, or a secondary output: the main output still reads it
        }
        if (!workItem.isLocal()) {
            if (workItem.getSourceDeleter().isPresent()) {
                try {
                    workItem.getSourceDeleter().get().delete();
                } catch (IOException e) {
                    throw CopybotException.ofResource(e, "write.delete-source.failed",
                            workItem.getSourceLocationDisplay(), target);
                }
                workItem.setDeleted(true);
            }
            return; // without deleter: not an original source Copybot could delete
        }
        Path source = workItem.getLocalLocation();
        try {
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(source, target)) {
                return; // defence in depth: the destination is the source itself, the only copy
            }
            Files.deleteIfExists(source);
            workItem.setDeleted(true);
        } catch (IOException e) {
            // the copy is done: the next run finds it identical, and retries the deletion with fullHash
            throw CopybotException.ofResource(e, "write.delete-source.failed", source, target);
        }
    }

    static boolean isFile(FileAlreadyExistsException e, Path target) {
        if (e.getFile() == null) {
            return false;
        }
        try {
            return Path.of(e.getFile()).toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
        } catch (InvalidPathException invalid) {
            return false;
        }
    }
}
