package com.copybot.plugin.embedded.actions;

import com.copybot.engine.resources.DiskTraffic;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Copies each item to a buffer folder on a fast local disk; the steps after it read the copy. The step holds the
 * disk of the item and the buffer's, the next ones the buffer's and their own: a card is read at its own speed
 * while a slower destination (a NAS) is written at its own. The copy is deleted once the item is finished
 * ({@link #afterItem}); the source stays the item's (the out step's deleteSource deletes the card's file).
 * <p>
 * Each run writes in its own folder "run-&lt;id&gt;" under the buffer folder, removed after the run; the run
 * folders an interrupted run left there more than a day ago are removed at the next one.
 */
public class FileBufferAction extends AbstractActionWithConfig<FileBufferConfig> implements IProcessAction {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(FileBufferAction.class);

    private static final int BUFFER_SIZE = 1 << 20;
    private static final String RUN_PREFIX = "run-";
    private static final Duration STALE = Duration.ofDays(1);

    private volatile Path folder;
    private volatile Path runFolder;
    /** The copy made for each item handed to this step, by identity: what afterItem deletes. */
    private final Map<WorkItem, Path> copies = Collections.synchronizedMap(new IdentityHashMap<>());
    /** The bytes of the copies being written: not yet taken from the free space. */
    private final AtomicLong writing = new AtomicLong();
    private final AtomicLong next = new AtomicLong();

    @Override
    protected Class<FileBufferConfig> getConfigClass() {
        return FileBufferConfig.class;
    }

    @Override
    public void loadConfig(JsonElement config) {
        super.loadConfig(config);
        String configured = getConfig() == null ? null : getConfig().folder();
        folder = (configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "copybot-buffer")
                : Path.of(configured)).toAbsolutePath();
    }

    private long keepFreeBytes() {
        return getConfig() == null ? new FileBufferConfig(null, null).keepFreeBytes() : getConfig().keepFreeBytes();
    }

    @Override
    public Set<Path> touchedPaths(WorkItem item) {
        return Set.of(folder);
    }

    @Override
    public void beforeAll() {
        removeStaleRuns();
    }

    @Override
    public List<WorkItem> doProcess(WorkItem item) {
        Long size = item.getMetadatas().getSize();
        long reserved = size == null ? 0 : size;
        try {
            Path run = runFolder();
            long free = Files.getFileStore(run).getUsableSpace() - writing.addAndGet(reserved);
            if (free < keepFreeBytes()) {
                LOG.info("plugin.embedded.file.buffer.full", item.getNameDisplay(), folder);
                return List.of(item); // read from where it is: slower, but nothing fails
            }
            String name = item.getNameDisplay() == null ? "item" : item.getNameDisplay(); // a URL item may have none
            Path copy = run.resolve(next.incrementAndGet() + "-" + name);
            copy(item, copy, size);
            copies.put(item, copy);
            return List.of(item.withContentFile(copy));
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "plugin.embedded.file.buffer.error.io", item.getNameDisplay(),
                    e.getLocalizedMessage());
        } finally {
            writing.addAndGet(-reserved);
        }
    }

    private void copy(WorkItem item, Path copy, Long size) throws IOException {
        try (InputStream in = item.openInputStream();
             OutputStream out = Files.newOutputStream(copy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            DiskTraffic.Counter disk = DiskTraffic.global().on(copy); // the gauge of the buffer's disk
            byte[] buffer = new byte[BUFFER_SIZE];
            long copied = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                disk.written(read);
                copied += read;
                if (size != null && size > 0) {
                    updatePercent((int) Math.min(100, copied * 100 / size));
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(copy); // no partial copy left behind
            throw e;
        }
    }

    /** Unchanged: the copy changes nothing to the targets. */
    @Override
    public Optional<List<WorkItem>> dryRun(WorkItem item) {
        return Optional.of(List.of(item));
    }

    @Override
    public void afterItem(WorkItem item) {
        Path copy = copies.remove(item);
        if (copy != null) {
            delete(copy);
        }
    }

    /** What is left (items left out of the run) goes with the run folder. */
    @Override
    public void afterAll() {
        Path run = runFolder;
        runFolder = null;
        copies.clear();
        if (run != null) {
            deleteTree(run);
        }
    }

    private synchronized Path runFolder() throws IOException {
        if (runFolder == null) {
            runFolder = Files.createDirectories(folder.resolve(RUN_PREFIX + UUID.randomUUID()));
        }
        return runFolder;
    }

    private void removeStaleRuns() {
        if (!Files.isDirectory(folder)) {
            return;
        }
        Instant limit = Instant.now().minus(STALE);
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(folder, RUN_PREFIX + "*")) {
            for (Path run : runs) {
                if (Files.isDirectory(run) && Files.getLastModifiedTime(run).toInstant().isBefore(limit)) {
                    deleteTree(run);
                }
            }
        } catch (IOException e) {
            LOG.warn(e, "plugin.embedded.file.buffer.cleanup.failed", folder, e.getLocalizedMessage());
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Collections.reverseOrder()).forEach(FileBufferAction::delete);
        } catch (IOException e) {
            LOG.warn(e, "plugin.embedded.file.buffer.cleanup.failed", root, e.getLocalizedMessage());
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn(e, "plugin.embedded.file.buffer.cleanup.failed", path, e.getLocalizedMessage());
        }
    }
}
