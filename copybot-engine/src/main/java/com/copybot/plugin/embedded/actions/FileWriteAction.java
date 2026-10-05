package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IOutAction;
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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private volatile FileWriteSettings settings;
    private volatile ConflictResolver conflicts;
    private volatile SafeFileWriter writer;

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
        settings = loaded;
    }

    @Override
    public List<String> configWarnings() {
        FileWriteSettings current = settings;
        return current == null ? List.of() : current.warnings();
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(Path.of(settings.outPattern().staticPrefix()));
    }

    /** Direct use outside the engine: a write under a new run id; a skipped item simply returns. */
    @Override
    public void writeItem(WorkItem workItem) {
        write(workItem, WriteContext.newRun());
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
            return writeResolvingAgain(workItem, target, context.runId());
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
    private static final class TargetTaken extends IOException {
        TargetTaken(FileAlreadyExistsException cause) {
            super(cause);
        }
    }

    private WriteResult writeResolvingAgain(WorkItem workItem, Path target, String runId) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return doWrite(workItem, target, runId);
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
        return settings.outPattern().resolve(workItem.getMetadatas().display());
    }

    private CopybotException missingKey(WorkItem workItem, OutPattern.Resolution resolution) {
        // URL items have no file name
        String name = workItem.getNameDisplay() != null ? workItem.getNameDisplay() : workItem.getSourceLocationDisplay();
        return CopybotException.ofResource("write.pattern.missing-key", String.join(", ", resolution.missing()), name);
    }

    private WriteResult doWrite(WorkItem workItem, Path target, String runId) throws IOException {
        updateStatus(new WorkStatus("Copy file " + workItem.getSourceLocationDisplay(), -1));

        ConflictResolver.Decision decision = conflicts.resolve(workItem, target);
        if (decision.isSkip()) {
            // only a full comparison proves the destination holds the whole content (spec safe-write §5);
            // a destination that is the source itself is the only copy: never deleted
            if (!decision.sameFile() && decision.identical() && settings.compare() == Compare.FULL_HASH) {
                deleteSource(workItem, decision.target());
            }
            return WriteResult.skipped(decision.target(), decision.skipReason());
        }

        SafeFileWriter.Written written;
        try {
            if (canBeMoved(workItem, decision.target())) {
                move(workItem, decision);
                return WriteResult.written(decision.target());
            }
            written = writer.write(workItem, decision.target(), decision.replaceExisting(), runId, this::updatePercent);
        } catch (FileAlreadyExistsException e) {
            if (isFile(e, decision.target())) {
                throw new TargetTaken(e); // resolved again; a temporary file name collision is not
            }
            throw e;
        }
        workItem.getMetadatas().raw().put(WorkItemMetadata.SHA256, written.sha256());
        deleteSource(workItem, decision.target());
        return WriteResult.written(decision.target());
    }

    /** The existing fast path (temporary source or "delete after", same file system): not rewritten, not hashed. */
    private static void move(WorkItem workItem, ConflictResolver.Decision decision) throws IOException {
        Path parent = decision.target().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (decision.replaceExisting()) {
            Files.move(workItem.getLocalLocation(), decision.target(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(workItem.getLocalLocation(), decision.target());
        }
        workItem.setDeleted(!workItem.isTempFile());
    }

    /**
     * deleteSource: once written and verified, or already at the destination and compared with fullHash.
     * Local sources only: a URL source (or its temporary copy) is kept, without error.
     *
     * @throws CopybotException write.delete-source.failed
     */
    private void deleteSource(WorkItem workItem, Path target) {
        if (!settings.deleteSource() || workItem.isDeleted() || !workItem.isLocal() || workItem.isTempFile()) {
            return; // nothing asked, already gone, or not an original local file Copybot could delete
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

    private static boolean isFile(FileAlreadyExistsException e, Path target) {
        if (e.getFile() == null) {
            return false;
        }
        try {
            return Path.of(e.getFile()).toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static boolean canBeMoved(WorkItem workItem, Path outPath) {
        // can be moved if either is a temp file or the original file with delete option enabled
        if ((workItem.isLocal() && workItem.isDeleteAfterCompletion()) || workItem.isTempFile()) {
            // can be moved if on the same disk
            return workItem.getLocalLocation().getFileSystem().provider() == outPath.getFileSystem().provider();
        }
        return false;
    }

}
