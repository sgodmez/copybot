package com.copybot.plugin.remote.core;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;
import com.google.gson.JsonElement;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/**
 * The input of a remote server, {@code <protocol>.read} (spec remote §4): lists a remote folder (its tree, unless not
 * recursive), each file an item whose content is read from the server at each open, on a connection of the step's
 * {@link RemotePool}; its source deleter deletes the remote file. The globs are file.read's; the hidden entries (a
 * name starting with a dot, Copybot's temporary files among them) are left out, as file.read leaves them out by
 * default. A protocol gives its connection ({@link #connect}) and names.
 * <p>
 * Each folder is signalled done once its tree is listed ({@link #signalsFolders()}, true unless the protocol has no
 * real folders). Without folder signals, a recursive listing is made at once when the protocol can
 * ({@link RemoteFs#listTree}, S3), in the server's order. A failure to connect or to list fails the listing.
 *
 * @param <C> the protocol's configuration record
 */
public abstract class RemoteRead<C extends RemoteReadSettings> extends AbstractActionWithConfig<C> implements IInAction {

    private static final Comparator<RemoteEntry> BY_NAME = Comparator.comparing(RemoteEntry::name);

    private volatile C config;
    private volatile String folder;
    private volatile RemoteGlobs globs;
    private volatile RemotePool pool;

    /** The protocol, as the action codes, the sources and the resources name it: "sftp". */
    protected abstract String protocol();

    /** The server, as the sources and the resource name it (an S3 bucket); never a user name or a secret. */
    protected abstract String host(C config);

    /** Opens a connection to the server of this configuration. */
    protected abstract RemoteFs connect(C config) throws IOException;

    /** "sftp.read", at the start of the configuration and listing errors. */
    protected String code() {
        return protocol() + ".read";
    }

    /**
     * @throws IllegalArgumentException the folder is missing or holds "..", a glob is not valid, minSize is negative,
     *                                  maxConnections is below 1
     */
    @Override
    public void loadConfig(JsonElement json) {
        super.loadConfig(json);
        C loaded = getConfig();
        if (loaded == null || loaded.folder() == null || loaded.folder().isBlank()) {
            throw new IllegalArgumentException(message("read.error.no-folder", code()));
        }
        if (loaded.minSize() != null && loaded.minSize() < 0) {
            throw new IllegalArgumentException(message("read.error.min-size", code()));
        }
        int maxConnections = RemoteText.maxConnections(getPlugin(), code(), loaded.maxConnections());
        try {
            globs = new RemoteGlobs(loaded.include(), loaded.exclude());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(message("read.error.invalid-glob", code(), e.getPattern()), e);
        }
        String normalized;
        try {
            normalized = RemotePaths.normalize(loaded.folder().strip()); // as the targets of a write
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message("read.error.invalid-folder", code(), loaded.folder()), e);
        }
        folder = normalized;
        config = loaded;
        pool = new RemotePool(() -> connect(loaded), maxConnections);
    }

    /** The server, also for the listing (a null item). */
    @Override
    public Set<String> requiredResources(WorkItem item) {
        return RemoteText.resources(protocol(), host(config));
    }

    @Override
    public boolean signalsFolders() {
        return true;
    }

    /** Closes the connections of the listing; the contents read later connect again (see {@link RemotePool}). */
    @Override
    public void afterAll() {
        RemotePool current = pool;
        if (current != null) {
            current.closeAll();
        }
    }

    @Override
    public void listFiles(Consumer<WorkItem> workItemConsumer) {
        listFiles(new ListingSink() {
            @Override
            public void item(WorkItem item) {
                workItemConsumer.accept(item);
            }

            @Override
            public void folderDone(String folder) {
                // a plain consumer has no use for the end of a folder
            }
        });
    }

    /**
     * Lists the folder. No connection is held while the sink is called: the sink may read a listed content at once
     * (the unfolding of an archive, on this thread), which borrows a connection of the same pool; held, it would wait
     * forever with maxConnections 1. Each folder is listed on a connection borrowed for that listing only; a tree
     * listed at once ({@link RemoteFs#listTree}) is kept in memory until its end, then given.
     *
     * @throws IllegalStateException the connection or the listing of a folder failed, naming the folder
     */
    @Override
    public void listFiles(ListingSink sink) {
        String root = folder;
        sink.root(root);
        String[] current = {root};
        try {
            if (isRecursive() && !signalsFolders()) {
                List<WorkItem> tree = new ArrayList<>();
                if (pool.call(fs -> fs.listTree(root, entry -> treeEntry(root, entry, tree::add)))) {
                    tree.forEach(sink::item);
                    return;
                }
            }
            walk(root, "", sink, current);
        } catch (IOException e) {
            throw new IllegalStateException(message("read.error.list", code(), display(current[0]), RemoteText.detail(e)), e);
        }
    }

    /**
     * Lists a folder: its files, then its sub-folders' trees (names in order), then tells it done. The folder is
     * listed on a borrowed connection, given back before its entries go to the sink.
     *
     * @param relative the folder relative to the listed one, "" for it, else ending with '/'
     * @param current  receives the folder being listed, for the message of a failure
     */
    private void walk(String dir, String relative, ListingSink sink, String[] current) throws IOException {
        current[0] = dir;
        List<RemoteEntry> entries = new ArrayList<>(pool.call(fs -> fs.list(dir)));
        entries.sort(BY_NAME);
        long minSize = config.minSize() == null ? 0 : config.minSize();
        List<RemoteEntry> folders = new ArrayList<>();
        for (RemoteEntry entry : entries) {
            if (entry.name().startsWith(".")) {
                continue; // hidden, or a temporary file of a write
            }
            if (entry.directory()) {
                folders.add(entry);
            } else if (entry.size() >= minSize && globs.selected(relative + entry.name())) {
                sink.item(item(dir, entry));
            }
        }
        if (isRecursive()) {
            for (RemoteEntry sub : folders) {
                String subRelative = relative + sub.name();
                if (!globs.excludedFolder(subRelative)) {
                    walk(RemotePaths.join(dir, sub.name()), subRelative + "/", sink, current);
                }
            }
        }
        if (signalsFolders()) {
            sink.folderDone(dir);
        }
    }

    /**
     * A file of a tree listed at once ({@link RemoteFs#listTree}), named by its path relative to the listed folder:
     * left out as {@link #walk} would leave it (a hidden name on its path, an excluded folder above it, its size, the
     * globs).
     */
    private void treeEntry(String root, RemoteEntry entry, Consumer<WorkItem> items) {
        String relative = entry.name();
        String[] names = relative.split("/", -1);
        for (int i = 0; i < names.length; i++) {
            if (names[i].isEmpty() || names[i].startsWith(".")) {
                return; // hidden, a temporary file of a write, or no name (a "folder//key")
            }
            if (i < names.length - 1
                    && globs.excludedFolder(String.join("/", Arrays.asList(names).subList(0, i + 1)))) {
                return;
            }
        }
        long minSize = config.minSize() == null ? 0 : config.minSize();
        if (entry.directory() || entry.size() < minSize || !globs.selected(relative)) {
            return;
        }
        String parent = RemotePaths.parent(relative);
        String dir = parent.isEmpty() ? root : RemotePaths.join(root, parent);
        items.accept(item(dir, RemoteEntry.file(RemotePaths.name(relative), entry.size(), entry.lastModified())));
    }

    private boolean isRecursive() {
        return config.recursive() == null || config.recursive();
    }

    private WorkItem item(String dir, RemoteEntry entry) {
        String path = RemotePaths.join(dir, entry.name());
        String display = display(path);
        RemotePool current = pool;
        Instant lastModified = entry.lastModified() != null ? entry.lastModified() : Instant.EPOCH;
        return WorkItem.remote(entry.name(), dir, display, entry.size(), lastModified,
                () -> open(current, path, display),
                () -> current.call(fs -> {
                    fs.delete(path);
                    return null;
                }));
    }

    /**
     * A new stream of the remote file, on a borrowed connection given back when the stream is closed.
     *
     * @throws UncheckedIOException the connection or the open failed (an {@link java.io.InterruptedIOException} when
     *                              interrupted while waiting for a connection)
     */
    private InputStream open(RemotePool current, String path, String display) {
        RemotePool.Lease lease;
        try {
            lease = current.borrow();
        } catch (IOException e) {
            throw new UncheckedIOException(message("read.error.open", display, RemoteText.detail(e)), e);
        }
        try {
            return new LeasedStream(lease.fs().open(path), lease);
        } catch (IOException | RuntimeException e) {
            lease.invalidate();
            lease.close();
            if (e instanceof IOException io) {
                throw new UncheckedIOException(message("read.error.open", display, RemoteText.detail(io)), io);
            }
            throw (RuntimeException) e;
        }
    }

    /** The stream of a remote file: gives its connection back when closed, closed instead when a read failed. */
    private static final class LeasedStream extends FilterInputStream {
        private final RemotePool.Lease lease;

        LeasedStream(InputStream in, RemotePool.Lease lease) {
            super(in);
            this.lease = lease;
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException | RuntimeException e) {
                lease.invalidate();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (IOException | RuntimeException e) {
                lease.invalidate();
                throw e;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return super.skip(n);
            } catch (IOException | RuntimeException e) {
                lease.invalidate();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch (IOException | RuntimeException e) {
                lease.invalidate();
                throw e;
            } finally {
                lease.close();
            }
        }
    }

    /** Where a remote path is, for the user: "sftp://host/in/a.jpg". */
    protected String display(String path) {
        return RemoteText.display(protocol(), host(config), path);
    }

    private String message(String key, Object... args) {
        return RemoteText.message(getPlugin(), key, args);
    }
}
