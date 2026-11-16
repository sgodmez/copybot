package com.copybot.plugin.remote.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A remote server in memory for the tests: files and folders by path ('/' between names), the connections made to it,
 * and failures to inject. Thread-safe.
 */
final class InMemoryServer {

    record Stored(byte[] content, Instant lastModified) {
    }

    private final Map<String, Stored> files = new TreeMap<>();
    private final Set<String> folders = ConcurrentHashMap.newKeySet();

    final AtomicInteger connects = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    /** Every operation, as "op path" (e.g. "upload /out/.a.jpg.run1.copybot-tmp"). */
    final List<String> operations = new CopyOnWriteArrayList<>();

    volatile boolean atomic;
    /** Whether a connection lists a whole tree at once ({@link RemoteFs#listTree}), as S3. */
    volatile boolean treeListing;
    volatile boolean failConnect;
    /** The paths whose listing fails. */
    volatile Predicate<String> failList = path -> false;
    /** The paths whose upload fails after its first bytes. */
    volatile Predicate<String> failUpload = path -> false;
    /** The size stat reports for the paths it matches is one byte more (a truncated or grown upload). */
    volatile Predicate<String> wrongSize = path -> false;
    volatile boolean failRename;
    /** Whether an upload keeps the date it is told (else the date of the upload). */
    volatile boolean keepsDates = true;
    /** The paths stat does not find (a file gone right after its upload). */
    volatile Predicate<String> vanished = path -> false;
    /** A close fails (against the contract), after the connection is closed. */
    volatile boolean failClose;
    /** An operation run on an interrupted thread fails, as a real network call would. */
    volatile boolean failWhenInterrupted = true;
    /** Called once the upload has read its content, before the file is stored. */
    volatile Runnable beforeStore = () -> {
    };

    InMemoryServer() {
        folders.add("/");
    }

    synchronized void put(String path, String content, Instant lastModified) {
        files.put(path, new Stored(content.getBytes(), lastModified));
        addFolders(RemotePaths.parent(path));
    }

    synchronized void folder(String path) {
        addFolders(path);
    }

    private void addFolders(String folder) {
        for (String f = folder; !f.isEmpty() && folders.add(f); f = RemotePaths.parent(f)) {
            if (f.equals("/")) {
                break;
            }
        }
    }

    synchronized Optional<String> content(String path) {
        Stored stored = files.get(path);
        return stored == null ? Optional.empty() : Optional.of(new String(stored.content()));
    }

    synchronized List<String> paths() {
        return new ArrayList<>(files.keySet());
    }

    int open() {
        return connects.get() - closes.get();
    }

    RemoteFs connect() throws IOException {
        if (failConnect) {
            throw new IOException("connection refused");
        }
        connects.incrementAndGet();
        return new Connection();
    }

    private void check(String operation, String path) throws IOException {
        operations.add(operation + " " + path);
        if (failWhenInterrupted && Thread.currentThread().isInterrupted()) {
            throw new IOException(operation + " on an interrupted thread");
        }
    }

    /** One connection: counts its close once; tells whether it is still usable. */
    final class Connection implements RemoteFs {
        private volatile boolean closed;
        volatile boolean usable = true;

        @Override
        public List<RemoteEntry> list(String folder) throws IOException {
            check("list", folder);
            if (failList.test(folder)) {
                throw new IOException("cannot list " + folder);
            }
            synchronized (InMemoryServer.this) {
                if (!folders.contains(folder)) {
                    throw new FileNotFoundException(folder);
                }
                List<RemoteEntry> entries = new ArrayList<>();
                for (String f : folders) {
                    if (!f.equals(folder) && RemotePaths.parent(f).equals(folder)) {
                        entries.add(RemoteEntry.folder(RemotePaths.name(f)));
                    }
                }
                files.forEach((path, stored) -> {
                    if (RemotePaths.parent(path).equals(folder)) {
                        entries.add(RemoteEntry.file(RemotePaths.name(path), stored.content().length, stored.lastModified()));
                    }
                });
                Collections.shuffle(entries); // no order promised
                return entries;
            }
        }

        /** The tree in one listing when {@link #treeListing} (as S3), else not supported. */
        @Override
        public boolean listTree(String folder, java.util.function.Consumer<RemoteEntry> found) throws IOException {
            if (!treeListing) {
                return false;
            }
            check("listTree", folder);
            String prefix = folder.endsWith("/") ? folder : folder + "/";
            List<RemoteEntry> entries = new ArrayList<>();
            synchronized (InMemoryServer.this) {
                files.forEach((path, stored) -> {
                    if (path.startsWith(prefix)) {
                        entries.add(RemoteEntry.file(path.substring(prefix.length()), stored.content().length,
                                stored.lastModified()));
                    }
                });
            }
            for (RemoteEntry entry : entries) {
                found.accept(entry); // outside the lock, as a page given while the listing goes on
            }
            return true;
        }

        @Override
        public InputStream open(String path) throws IOException {
            check("open", path);
            synchronized (InMemoryServer.this) {
                Stored stored = files.get(path);
                if (stored == null) {
                    throw new FileNotFoundException(path);
                }
                return new ByteArrayInputStream(stored.content());
            }
        }

        @Override
        public Optional<RemoteEntry> stat(String path) throws IOException {
            check("stat", path);
            synchronized (InMemoryServer.this) {
                Stored stored = files.get(path);
                if (stored != null && !vanished.test(path)) {
                    long size = stored.content().length + (wrongSize.test(path) ? 1 : 0);
                    return Optional.of(RemoteEntry.file(RemotePaths.name(path), size, stored.lastModified()));
                }
                return folders.contains(path) ? Optional.of(RemoteEntry.folder(RemotePaths.name(path))) : Optional.empty();
            }
        }

        @Override
        public void mkdirs(String folder) throws IOException {
            check("mkdirs", folder);
            folder(folder);
        }

        @Override
        public void upload(String path, InputStream content, long size, Instant lastModified) throws IOException {
            check("upload", path);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2];
            int n;
            try {
                while ((n = content.read(buffer)) >= 0) {
                    bytes.write(buffer, 0, n);
                    if (failUpload.test(path)) {
                        throw new IOException("connection reset");
                    }
                }
            } catch (IOException e) {
                // a partial file stays, as on a real server
                store(path, bytes.toByteArray(), null);
                throw e;
            }
            beforeStore.run();
            store(path, bytes.toByteArray(), lastModified);
        }

        private void store(String path, byte[] content, Instant lastModified) {
            synchronized (InMemoryServer.this) {
                files.put(path, new Stored(content, keepsDates && lastModified != null ? lastModified : Instant.now()));
            }
        }

        @Override
        public void rename(String from, String to, boolean replace) throws IOException {
            check("rename", from + " -> " + to + (replace ? " (replace)" : ""));
            if (failRename) {
                throw new IOException("rename refused");
            }
            synchronized (InMemoryServer.this) {
                if (!replace && files.containsKey(to)) {
                    throw new FileAlreadyExistsException(to);
                }
                Stored stored = files.remove(from);
                if (stored == null) {
                    throw new FileNotFoundException(from);
                }
                files.put(to, stored);
            }
        }

        @Override
        public void delete(String path) throws IOException {
            check("delete", path);
            synchronized (InMemoryServer.this) {
                files.remove(path);
            }
        }

        @Override
        public boolean atomicUpload() {
            return atomic;
        }

        @Override
        public boolean keepsLastModified() {
            return keepsDates;
        }

        @Override
        public boolean isUsable() {
            return usable;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closes.incrementAndGet();
            }
            if (failClose) {
                throw new IllegalStateException("close failed");
            }
        }
    }
}
