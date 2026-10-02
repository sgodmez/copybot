package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.embedded.actions.FileWriteSettings.WriteMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Writes one item to its final target (spec safe-write §3, §4): through a hidden temporary file of the
 * target directory then a move (tempAndRename), or straight under the final name (direct); the SHA-256
 * is computed on the fly, then the written file is verified.
 * <p>
 * A failure never leaves a temporary file this write created, nor a partial file this write created, and
 * never deletes a file this write did not create (another item's temporary file). A failed verification
 * always deletes the written file: in tempAndRename that is the temporary file (an existing target stays
 * intact); in direct mode with replaceExisting it is the overwritten target, whose original content was
 * lost as soon as the write started (spec §4).
 * <p>
 * Two concurrent writes to the same target never interleave: the second one fails with
 * FileAlreadyExistsException before opening anything.
 * <p>
 * Known limits:
 * <ul>
 *     <li>Without replaceExisting, the final move uses no option. On POSIX the JDK checks that the target
 *     does not exist and then calls {@code rename(2)}, which replaces silently: a file created by another
 *     process between the two can still be replaced. There is no portable no-replace rename in the JDK.</li>
 *     <li>readBack reads the written file again through the file system; over SMB (or any network share
 *     with client-side caching) it may read the client's cache rather than the server's disk.</li>
 * </ul>
 */
final class SafeFileWriter {

    static final String TEMP_SUFFIX = ".copybot-tmp";

    /** A file name limit: 255 bytes (ext4...) or 255 UTF-16 units (NTFS); the UTF-8 length bounds both. */
    static final int MAX_NAME_BYTES = 255;

    private static final int BUFFER_SIZE = 8192;

    /** What was copied: the number of bytes and their SHA-256, hex-encoded. */
    record Written(long size, String sha256) {
    }

    private record CleanedDir(String runId, Path dir) {
    }

    private final FileWriteSettings settings;

    /** The directories already cleaned of orphan temporary files, for the latest run only. */
    private final Set<CleanedDir> cleanedDirs = ConcurrentHashMap.newKeySet();

    /** The normalized absolute targets being written right now. */
    private final Set<Path> inFlight = ConcurrentHashMap.newKeySet();

    /** Visible for tests: receives the written file right before it is verified (e.g. to alter it). */
    private volatile Consumer<Path> beforeVerify = path -> {
    };

    SafeFileWriter(FileWriteSettings settings) {
        this.settings = settings;
    }

    void setBeforeVerify(Consumer<Path> beforeVerify) {
        this.beforeVerify = beforeVerify;
    }

    /**
     * @param replaceExisting overwrite the target; otherwise an existing target is never replaced (the
     *                        write fails with FileAlreadyExistsException)
     * @param runId           the execution: names the temporary file, spares its own temporary files
     * @param percent         receives the progress of the copy, when the item size is known
     * @throws FileAlreadyExistsException the target exists without replaceExisting, the target is being
     *                                    written by another call, or the temporary file name is already
     *                                    taken (left untouched)
     * @throws CopybotException           write.verify.size / write.verify.hash when the verification fails
     */
    Written write(WorkItem item, Path target, boolean replaceExisting, String runId, IntConsumer percent) throws IOException {
        Path key = target.toAbsolutePath().normalize();
        if (!inFlight.add(key)) {
            throw new FileAlreadyExistsException(target.toString(), null, "already being written");
        }
        try {
            Path dir = key.getParent();
            Files.createDirectories(dir);
            cleanOrphans(dir, runId);
            if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { // a dangling link exists too
                throw new FileAlreadyExistsException(target.toString()); // early: no copy for nothing
            }
            return settings.writeMode() == WriteMode.TEMP_AND_RENAME
                    ? writeThroughTemp(item, target, dir.resolve(tempName(target.getFileName().toString(), runId)),
                    replaceExisting, percent)
                    : writeDirect(item, target, replaceExisting, percent);
        } finally {
            inFlight.remove(key);
        }
    }

    /**
     * The temporary file is verified before the move: a failed write never touches an existing target.
     * It is created before the try: a name collision fails with FileAlreadyExistsException and never
     * deletes the other file.
     */
    private Written writeThroughTemp(WorkItem item, Path target, Path temp, boolean replaceExisting,
                                     IntConsumer percent) throws IOException {
        FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Written written = copyAndClose(item, channel, percent);
            preserveDate(item, temp);
            verify(item, temp, target, written);
            moveIntoPlace(temp, target, replaceExisting);
            return written;
        } catch (Throwable e) {
            deleteQuietly(temp, e);
            throw e;
        }
    }

    /** A failed copy deletes the file only when this write created it; a failed verification always does. */
    private Written writeDirect(WorkItem item, Path target, boolean replaceExisting, IntConsumer percent) throws IOException {
        boolean created = true;
        FileChannel channel;
        try {
            channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            if (!replaceExisting) {
                throw e; // appeared meanwhile: never replaced
            }
            created = false;
            channel = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        boolean complete = false;
        try {
            Written written = copyAndClose(item, channel, percent);
            complete = true;
            preserveDate(item, target);
            verify(item, target, target, written);
            return written;
        } catch (Throwable e) {
            if (created || complete) {
                deleteQuietly(target, e);
            }
            throw e;
        }
    }

    /**
     * Copies the item into the channel, then closes it. When the source is deleted after the copy, the
     * written bytes are forced to the storage first: the source must never go while the copy is only in cache.
     */
    private Written copyAndClose(WorkItem item, FileChannel channel, IntConsumer percent) throws IOException {
        try (channel) {
            Written written = copy(item, Channels.newOutputStream(channel), percent);
            if (settings.deleteSource()) {
                channel.force(true);
            }
            return written;
        }
    }

    private static Written copy(WorkItem item, OutputStream out, IntConsumer percent) throws IOException {
        MessageDigest digest = FileComparison.newSha256();
        Long size = item.getMetadatas().getSize();
        long transferred = 0;
        try (InputStream in = item.openInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer, 0, BUFFER_SIZE)) >= 0) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                transferred += read;
                if (size != null && size > 0) {
                    // multiply first: size > transferred would floor to 0; a wrong announced size stays <= 100
                    percent.accept((int) Math.min(100, transferred * 100 / size));
                }
            }
        }
        return new Written(transferred, FileComparison.hex(digest.digest()));
    }

    /**
     * The expected size is the size metadata, else the bytes copied.
     *
     * @param file   the file written (the temporary file in tempAndRename)
     * @param target the final target, quoted in the messages
     */
    private void verify(WorkItem item, Path file, Path target, Written written) throws IOException {
        beforeVerify.accept(file);
        Long expected = item.getMetadatas().getSize();
        long expectedSize = expected != null ? expected : written.size();
        switch (settings.verify()) {
            case NONE -> checkSize(target, expectedSize, written.size());
            case SIZE -> checkSize(target, expectedSize, Files.size(file));
            case READ_BACK -> {
                checkSize(target, expectedSize, Files.size(file));
                String readBack;
                try (InputStream in = Files.newInputStream(file)) {
                    readBack = FileComparison.hex(FileComparison.sha256(in));
                }
                if (!readBack.equals(written.sha256())) {
                    throw CopybotException.ofResource("write.verify.hash", target);
                }
            }
        }
    }

    private static void checkSize(Path target, long expected, long actual) {
        if (actual != expected) {
            throw CopybotException.ofResource("write.verify.size", target, String.valueOf(expected), String.valueOf(actual));
        }
    }

    /** The copy keeps the source modification date: otherwise "sizeAndDate" would never recognise it. */
    private static void preserveDate(WorkItem item, Path file) throws IOException {
        Optional<Instant> date = FileComparison.sourceDate(item);
        if (date.isPresent()) {
            Files.setLastModifiedTime(file, FileTime.from(date.get()));
        }
    }

    /**
     * Without replaceExisting: a plain move, which fails if a file appeared meanwhile (see the class
     * javadoc for the POSIX race). With replaceExisting: an atomic replace only. Where the file system
     * cannot replace atomically, the non-atomic delete-then-rename is never used on an existing target:
     * a plain move is tried, which succeeds only if the target is gone; otherwise the item fails and the
     * original target stays intact (the caller deletes the temporary file).
     */
    private static void moveIntoPlace(Path temp, Path target, boolean replaceExisting) throws IOException {
        if (!replaceExisting) {
            Files.move(temp, target);
            return;
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            try {
                Files.move(temp, target);
            } catch (FileAlreadyExistsException exists) {
                atomicUnsupported.addSuppressed(exists);
                throw atomicUnsupported;
            }
        }
    }

    /**
     * First write of this run into dir: deletes the temporary files (regular files only, links not
     * followed) that other runs left behind (a crash), never those of the current run. Not recursive.
     * Best effort: a file that cannot be deleted is left for a next run. A new run forgets the directories
     * cleaned by the previous ones, so the set does not grow across runs.
     * <p>
     * Power-loss window (tempAndRename with deleteSource): the data is forced to the storage before the
     * move, but the rename itself (a directory entry) is not; the source is deleted right after it. A power
     * loss in between can leave the verified copy only under its temporary name, with the source already
     * gone on its own storage; this cleaning then deletes that temporary file on the next run. Not
     * handled: forcing the directory is not portable in the JDK (it fails on Windows).
     */
    private void cleanOrphans(Path dir, String runId) {
        if (!cleanedDirs.add(new CleanedDir(runId, dir))) {
            return;
        }
        cleanedDirs.removeIf(cleaned -> !cleaned.runId().equals(runId));
        try (DirectoryStream<Path> temps = Files.newDirectoryStream(dir, ".*" + TEMP_SUFFIX)) {
            for (Path temp : temps) {
                if (Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)
                        && !runId.equals(runIdOf(temp.getFileName().toString()))) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException e) {
                        // e.g. still open by another running copy: not this write's business
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // the listing failed: the write itself goes on
        }
    }

    /**
     * ".name.runId.copybot-tmp". When that exceeds {@value #MAX_NAME_BYTES} bytes (a target name that fits
     * the limit, but not with the 50 more characters of a run id), the name is shortened to a prefix
     * followed by "~" and a hash of the whole name: still unique per target, still hidden and still
     * recognised by the orphan cleaning.
     */
    static String tempName(String name, String runId) {
        String tail = "." + runId + TEMP_SUFFIX;
        String full = "." + name + tail;
        if (utf8Length(full) <= MAX_NAME_BYTES) {
            return full;
        }
        String hash = "~" + FileComparison.hex(FileComparison.newSha256().digest(name.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
        int budget = MAX_NAME_BYTES - utf8Length("." + hash + tail);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i); // never splits a surrogate pair
            String next = new String(Character.toChars(codePoint));
            budget -= utf8Length(next);
            if (budget < 0) {
                break;
            }
            prefix.append(next);
            i += Character.charCount(codePoint);
        }
        return "." + prefix + hash + tail;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** ".name.runId.copybot-tmp" gives runId (a run id has no dot, a name may have several). */
    static String runIdOf(String tempName) {
        String stem = tempName.substring(0, tempName.length() - TEMP_SUFFIX.length());
        return stem.substring(stem.lastIndexOf('.') + 1);
    }

    private static void deleteQuietly(Path file, Throwable failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }
}
