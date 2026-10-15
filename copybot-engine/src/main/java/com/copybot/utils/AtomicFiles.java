package com.copybot.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replaces a small file (a state, a configuration) so that a crash or a power cut leaves either the old content or
 * the new one, never a truncated file.
 */
public final class AtomicFiles {

    private static final System.Logger LOG = System.getLogger(AtomicFiles.class.getName());

    private AtomicFiles() {
    }

    /** {@link #write(Path, byte[])} of the text in UTF-8. */
    public static void write(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes the bytes to a temporary file of a unique name in the target's directory, forces them to the disk,
     * then moves that file over the target: atomically when the file system can, replacing it otherwise. On
     * failure the target is left as it was and the temporary file is deleted (best effort).
     *
     * @throws IOException the directory cannot take the temporary file, the write, the flush or the move failed
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path directory = absolute.getParent();
        Path temp = Files.createTempFile(directory, "." + absolute.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true); // on the disk before the move makes it the target
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup); // the original failure is the one worth reporting
            }
            throw e;
        }
        forceDirectory(directory);
    }

    /**
     * Forces the directory entry of the move to the disk where the platform allows it (not on Windows, where a
     * directory cannot be opened): best effort, the file itself is already safe.
     */
    private static void forceDirectory(Path directory) {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot force the directory " + directory + " to the disk", e);
        }
    }
}
