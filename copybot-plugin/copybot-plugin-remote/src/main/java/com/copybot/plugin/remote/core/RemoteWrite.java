package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.api.pattern.OutPattern;
import com.google.gson.JsonElement;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * The output to a remote server, {@code <protocol>.write} (spec remote §5): each item goes to the remote path its
 * pattern gives. An existing target is compared (size, and date within 2 seconds with "sizeAndDate"; never the
 * content), then skipped, renamed "name (1).ext", overwritten or refused. The content is sent to a temporary name
 * {@code .<name>.<runId>-<n>.copybot-tmp} of the target's folder (n: the attempt of the step), with the item's date
 * of modification when the server keeps it, its remote size checked, then renamed to the target (a protocol whose
 * upload is atomic writes under the final name): a failure or a cancel deletes what was sent. The
 * source is deleted afterwards when asked, never when kept ({@link WorkItem#isSourceKept()}).
 * <p>
 * Two items of the step with one target are serialized (the second finds the first). The target is not a local path:
 * the engine is told none ({@link WriteResult} without target, no {@link #resolveTarget}, the target check unknown,
 * no written file reported), the messages name the remote target.
 *
 * @param <C> the protocol's configuration record
 */
public abstract class RemoteWrite<C extends RemoteWriteSettings> extends AbstractActionWithConfig<C>
        implements IOutAction {

    /** The difference of dates still "the same date" (file systems keep 2 s, FTP and S3 1 s). */
    static final Duration DATE_TOLERANCE = Duration.ofSeconds(2);

    enum Compare {
        SIZE("size"), SIZE_AND_DATE("sizeAndDate");

        final String jsonName;

        Compare(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    enum Policy {
        SKIP("skip"), RENAME("rename"), OVERWRITE("overwrite"), ERROR("error");

        final String jsonName;

        Policy(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    enum MissingKey {
        ERROR("error"), SKIP("skip"), LITERAL("literal");

        final String jsonName;

        MissingKey(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    /** The validated configuration, defaults applied. */
    private record Settings(OutPattern outPattern, MissingKey onMissingKey, Compare compare, Policy ifIdentical,
                            Policy ifDifferent, boolean deleteSource) {
    }

    /** Where an item goes: the target, replaced or not, or why it is skipped. */
    private record Decision(String target, boolean replace, String skipReason) {
    }

    /** The base targets (from the pattern) being resolved and written right now. */
    private final ConcurrentHashMap<String, TargetLock> targetLocks = new ConcurrentHashMap<>();

    /** The parsed own patterns of the items, by text (few distinct ones). */
    private final ConcurrentHashMap<String, OutPattern> ownPatterns = new ConcurrentHashMap<>();

    private static final System.Logger LOGGER = System.getLogger(RemoteWrite.class.getName());

    /** Whether the warnings were logged in this phase (reset by {@link #beforeAll}). */
    private final AtomicBoolean datesWarned = new AtomicBoolean();
    private final AtomicBoolean sizeWarned = new AtomicBoolean();
    private final AtomicBoolean keptWarned = new AtomicBoolean();

    /** The uploads through a temporary file made by this step: the number of each one is in its name. */
    private final AtomicLong attempts = new AtomicLong();

    private volatile C config;
    private volatile Settings settings;
    private volatile RemotePool pool;

    /** The protocol, as the action codes, the targets and the resources name it: "sftp". */
    protected abstract String protocol();

    /** The server, as the targets and the resource name it (an S3 bucket); never a user name or a secret. */
    protected abstract String host(C config);

    /** Opens a connection to the server of this configuration. */
    protected abstract RemoteFs connect(C config) throws IOException;

    /** "sftp.write", at the start of the configuration errors. */
    protected String code() {
        return protocol() + ".write";
    }

    /**
     * @throws IllegalArgumentException the pattern is missing, a value is not one of the allowed ones,
     *                                  maxConnections is below 1
     * @throws com.copybot.exception.CopybotException the pattern is not valid (pattern.syntax)
     */
    @Override
    public void loadConfig(JsonElement json) {
        super.loadConfig(json);
        C loaded = getConfig();
        if (loaded == null || loaded.outPattern() == null || loaded.outPattern().isBlank()) {
            throw new IllegalArgumentException(message("write.error.no-out-pattern", code()));
        }
        OutPattern pattern = OutPattern.parse(loaded.outPattern());
        RemoteOnConflict onConflict = loaded.onConflict();
        Settings parsed = new Settings(pattern,
                parse("onMissingKey", loaded.onMissingKey(), MissingKey.ERROR, MissingKey.values(), m -> m.jsonName),
                parse("onConflict.compare", onConflict == null ? null : onConflict.compare(), Compare.SIZE,
                        Compare.values(), c -> c.jsonName),
                parse("onConflict.ifIdentical", onConflict == null ? null : onConflict.ifIdentical(), Policy.SKIP,
                        Policy.values(), p -> p.jsonName),
                parse("onConflict.ifDifferent", onConflict == null ? null : onConflict.ifDifferent(), Policy.RENAME,
                        Policy.values(), p -> p.jsonName),
                Boolean.TRUE.equals(loaded.deleteSource()));
        int maxConnections = RemoteText.maxConnections(getPlugin(), code(), loaded.maxConnections());
        config = loaded;
        settings = parsed;
        pool = new RemotePool(() -> connect(loaded), maxConnections);
    }

    private <E> E parse(String field, String value, E defaultValue, E[] values,
                        java.util.function.Function<E, String> jsonName) {
        if (value == null) {
            return defaultValue;
        }
        for (E constant : values) {
            if (jsonName.apply(constant).equals(value)) {
                return constant;
            }
        }
        String expected = Arrays.stream(values).map(jsonName).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(message("write.error.unknown-value", code(), field, value, expected));
    }

    @Override
    public Set<String> requiredResources(WorkItem item) {
        return RemoteText.resources(protocol(), host(config));
    }

    @Override
    public Optional<String> defaultOutPattern() {
        return Optional.of(config.outPattern());
    }

    /** From the pattern, as file.write (the resume from the destination is refused anyway: no local target). */
    @Override
    public Optional<Boolean> targetDirectoryVaries() {
        return Optional.of(settings.outPattern().directoryVaries());
    }

    @Override
    public void beforeAll() {
        datesWarned.set(false);
        sizeWarned.set(false);
        keptWarned.set(false);
    }

    /** Closes the connections of the step. */
    @Override
    public void afterAll() {
        RemotePool current = pool;
        if (current != null) {
            current.closeAll();
        }
    }

    /**
     * @throws IllegalStateException the item fails: no value for the pattern ("error"), an invalid remote path, a
     *                               conflict "error", a failed connection, upload, verification or rename, a cancel,
     *                               a source not deleted; the message names the remote target
     */
    @Override
    public WriteResult write(WorkItem workItem, WriteContext context) {
        Settings current = settings;
        if (workItem.isSourceKept() && current.deleteSource() && keptWarned.compareAndSet(false, true)) {
            // an out step has no run warning channel: logged, once per phase and step (as file.write)
            LOGGER.log(System.Logger.Level.WARNING,
                    code() + ": deleteSource is ignored for a secondary output, the source is kept");
        }
        OutPattern.Resolution resolution = pattern(workItem, current).resolve(workItem.getMetadatas().display());
        if (!resolution.complete()) {
            switch (current.onMissingKey()) {
                case ERROR -> throw new IllegalStateException(message("write.error.missing-key", code(),
                        String.join(", ", resolution.missing()), name(workItem)));
                case SKIP -> {
                    return WriteResult.skipped(null, message("write.skip.missing-key",
                            String.join(", ", resolution.missing())));
                }
                case LITERAL -> { } // the expression stays as written, as file.write
            }
        }
        String target;
        try {
            target = RemotePaths.normalize(resolution.text());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(message("write.error.invalid-target", code(), resolution.text()));
        }
        if (target.isEmpty() || target.equals("/")) {
            throw new IllegalStateException(message("write.error.invalid-target", code(), resolution.text()));
        }
        TargetLock lock;
        try {
            lock = lockTarget(target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message("write.error.io", display(target), RemoteText.detail(e)), e);
        }
        try {
            return pool.call(fs -> write(fs, workItem, target, current, context));
        } catch (IOException e) {
            throw new IllegalStateException(message("write.error.io", display(target), RemoteText.detail(e)), e);
        } finally {
            unlockTarget(target, lock);
        }
    }

    private WriteResult write(RemoteFs fs, WorkItem item, String target, Settings current, WriteContext context)
            throws IOException {
        Decision decision = resolve(fs, item, target, current);
        if (decision.skipReason() != null) {
            // no content compared: the destination is not proven to hold the content, the source is kept
            return WriteResult.skipped(null, decision.skipReason());
        }
        ItemProgress progress = context.progress();
        progress.report(new WorkStatus(message("write.status", display(decision.target())), -1));
        upload(fs, item, decision, context.runId(), progress);
        deleteSource(item, current, decision.target());
        return WriteResult.written(null);
    }

    /**
     * Free target: written there. Existing target: compared, then ifIdentical or ifDifferent applies; "rename" tries
     * "name (1).ext", "name (2).ext"... each existing candidate compared in turn, so that an identical copy already
     * renamed is found again. A folder is "different" and never replaced.
     */
    private Decision resolve(RemoteFs fs, WorkItem item, String target, Settings current) throws IOException {
        String candidate = target;
        for (int n = 1; ; n++) {
            Optional<RemoteEntry> existing = fs.stat(candidate);
            if (existing.isEmpty()) {
                return new Decision(candidate, false, null);
            }
            boolean identical = !existing.get().directory() && identical(item, existing.get(), compare(fs, current));
            Policy policy = identical ? current.ifIdentical() : current.ifDifferent();
            switch (policy) {
                case SKIP -> {
                    return new Decision(candidate, false, message(identical ? "write.skip.identical" : "write.skip.exists",
                            display(candidate)));
                }
                case OVERWRITE -> {
                    if (existing.get().directory()) {
                        throw new IllegalStateException(message("write.error.folder", display(candidate)));
                    }
                    return new Decision(candidate, true, null);
                }
                case ERROR -> throw new IllegalStateException(message("write.error.conflict", display(candidate)));
                case RENAME -> candidate = numbered(target, n);
            }
        }
    }

    /**
     * The comparison of the configuration, or the sizes alone when the server does not keep the dates written
     * ({@link RemoteFs#keepsLastModified()}): every target written by an earlier run would have another date, and
     * "sizeAndDate" would make a new copy of each item at each run. Logged once per step and phase.
     */
    private Compare compare(RemoteFs fs, Settings current) {
        if (current.compare() == Compare.SIZE_AND_DATE && !fs.keepsLastModified()) {
            if (datesWarned.compareAndSet(false, true)) {
                LOGGER.log(System.Logger.Level.WARNING, code() + ": the server does not keep the dates written,"
                        + " onConflict.compare sizeAndDate compares the sizes only");
            }
            return Compare.SIZE;
        }
        return current.compare();
    }

    /**
     * Same size (and date within {@link #DATE_TOLERANCE}). An item of unknown size (a rewritten content without
     * size) is never identical: counting its content for each existing target would cost a read; logged once.
     */
    private boolean identical(WorkItem item, RemoteEntry existing, Compare compare) {
        Long size = item.getMetadatas().getSize();
        if (size == null) {
            if (sizeWarned.compareAndSet(false, true)) {
                LOGGER.log(System.Logger.Level.WARNING, code() + ": an item without size is never found identical"
                        + " to an existing target");
            }
            return false;
        }
        if (size != existing.size()) {
            return false;
        }
        if (compare == Compare.SIZE) {
            return true;
        }
        Optional<Instant> date = item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED);
        return date.isPresent() && existing.lastModified() != null
                && Duration.between(date.get(), existing.lastModified()).abs().compareTo(DATE_TOLERANCE) <= 0;
    }

    /** "name (n).ext", or "name (n)" without extension; the leading dot of a hidden file is not an extension. */
    static String numbered(String target, int n) {
        String name = RemotePaths.name(target);
        int dot = name.lastIndexOf('.');
        String numbered = dot > 0
                ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                : name + " (" + n + ")";
        return RemotePaths.join(RemotePaths.parent(target), numbered);
    }

    /**
     * Sends the content: to a temporary name then renamed, or under the final name when the upload is atomic; the
     * size sent must be the item's, the size on the server the size sent. A failure (or a cancel) deletes what was
     * sent, unless it replaced the target in place (an atomic overwrite: the old file is gone either way).
     */
    private void upload(RemoteFs fs, WorkItem item, Decision decision, String runId, ItemProgress progress)
            throws IOException {
        String target = decision.target();
        String folder = RemotePaths.parent(target);
        if (!folder.isEmpty()) {
            fs.mkdirs(folder);
        }
        boolean atomic = fs.atomicUpload();
        // the attempt number: two writes ending on one candidate (a lock per base target) never share a temporary file
        String sent = atomic ? target : RemotePaths.join(folder,
                "." + RemotePaths.name(target) + "." + runId + "-" + attempts.incrementAndGet() + ".copybot-tmp");
        boolean done = false;
        try {
            long size = send(fs, item, sent, target, progress);
            Optional<RemoteEntry> written = fs.stat(sent);
            if (written.isEmpty()) {
                throw new IllegalStateException(message("write.error.size-missing", display(target)));
            }
            if (written.get().size() != size) {
                throw new IllegalStateException(message("write.error.size-remote", display(target),
                        written.get().size(), size));
            }
            if (!atomic) {
                fs.rename(sent, target, decision.replace());
            }
            done = true;
        } finally {
            if (!done && !(atomic && decision.replace())) {
                deleteQuietly(fs, sent);
            }
        }
    }

    /** Uploads the content of the item to {@code path}, following its progress; returns the bytes sent. */
    private long send(RemoteFs fs, WorkItem item, String path, String target, ItemProgress progress) throws IOException {
        Long expected = item.getMetadatas().getSize();
        long size = expected == null ? -1 : expected;
        InputStream source;
        try {
            source = item.openInputStream();
        } catch (IOException | UncheckedIOException e) {
            // the source failed, not this connection: not an IOException, which would close it (a remote source:
            // its message names it)
            Throwable cause = e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e;
            throw new IllegalStateException(message("write.error.io", display(target), RemoteText.detail(cause)), cause);
        }
        Instant lastModified = item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElse(null);
        try (CountingStream content = new CountingStream(source, size, progress)) {
            fs.upload(path, content, size, lastModified);
            if (size >= 0 && content.count != size) {
                throw new IllegalStateException(message("write.error.size-sent", display(target), content.count, size));
            }
            return content.count;
        }
    }

    /** Deletes what a failed upload left, on the interrupted thread of a cancel too; a failure is kept as suppressed. */
    private static void deleteQuietly(RemoteFs fs, String path) {
        boolean interrupted = Thread.interrupted(); // the clean-up is a call to the server like any other
        try {
            fs.delete(path);
        } catch (IOException | RuntimeException e) {
            // the connection is closed by the failure anyway; a crashed run's temporary files have the run id
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The content sent, counted: tells the progress of the item, and stops the upload at a cancel (the thread
     * interrupted) with an {@link InterruptedIOException}.
     */
    private static final class CountingStream extends FilterInputStream {
        private final long size;
        private final ItemProgress progress;
        long count;
        private int percent = -1;

        CountingStream(InputStream in, long size, ItemProgress progress) {
            super(in);
            this.size = size;
            this.progress = progress;
        }

        private void checkInterrupt() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("interrupted");
            }
        }

        private void counted(long n) {
            if (n <= 0) {
                return;
            }
            count += n;
            if (size > 0) {
                int now = (int) Math.min(100, count * 100 / size);
                if (now != percent) {
                    percent = now;
                    progress.percent(now);
                }
            }
        }

        @Override
        public int read() throws IOException {
            checkInterrupt();
            int b = in.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkInterrupt();
            int n = in.read(b, off, len);
            counted(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            checkInterrupt();
            long skipped = in.skip(n);
            counted(skipped);
            return skipped;
        }
    }

    /**
     * deleteSource, once written and verified: a local source is deleted, another one through its source deleter (a
     * remote file); a source without deleter is kept. Never for a source kept for another output.
     *
     * @throws IllegalStateException the source is not deleted, naming it and the target
     */
    private void deleteSource(WorkItem item, Settings current, String target) {
        if (!current.deleteSource() || item.isDeleted() || item.isSourceKept()) {
            return;
        }
        try {
            if (item.isLocal()) {
                Files.deleteIfExists(item.getLocalLocation());
            } else if (item.getSourceDeleter().isPresent()) {
                item.getSourceDeleter().get().delete();
            } else {
                return; // not an original source Copybot could delete
            }
        } catch (IOException e) {
            throw new IllegalStateException(message("write.error.delete-source", item.getSourceLocationDisplay(),
                    display(target), RemoteText.detail(e)), e);
        }
        item.setDeleted(true);
    }

    /** The item's own pattern when it has one (spec own-out-pattern §1), else the configured one. */
    private OutPattern pattern(WorkItem item, Settings current) {
        return item.getOutPattern().map(text -> ownPatterns.computeIfAbsent(text, OutPattern::parse))
                .orElseGet(current::outPattern);
    }

    private static String name(WorkItem item) {
        return item.getNameDisplay() != null ? item.getNameDisplay() : item.getSourceLocationDisplay();
    }

    /** Where a remote path is, for the user: "sftp://host/out/a.jpg". */
    protected String display(String path) {
        return RemoteText.display(protocol(), host(config), path);
    }

    private String message(String key, Object... args) {
        return RemoteText.message(getPlugin(), key, args);
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

    private TargetLock lockTarget(String key) throws InterruptedException {
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

    private void unlockTarget(String key, TargetLock targetLock) {
        targetLock.lock.unlock();
        releaseTarget(key);
    }

    private void releaseTarget(String key) {
        targetLocks.computeIfPresent(key, (k, l) -> --l.users == 0 ? null : l);
    }
}
