package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Recognises an existing target identical to the item being written (spec safe-write §2) and computes
 * the SHA-256 of a content (§4). The sizes are always compared first: different sizes are never read.
 */
final class FileComparison {

    /** partialHash: this many bytes at the start and at the end of the file. */
    static final int PARTIAL_CHUNK = 64 * 1024;

    /** sizeAndDate: FAT (memory cards) stores modification times with a 2 s resolution. */
    static final Duration DATE_TOLERANCE = Duration.ofSeconds(2);

    private static final int BUFFER_SIZE = 8192;

    private FileComparison() {
    }

    static boolean identical(WorkItem item, Path target, Compare compare) throws IOException {
        long size = sourceSize(item);
        if (size != Files.size(target)) {
            return false;
        }
        return switch (compare) {
            case SIZE -> true;
            case SIZE_AND_DATE -> {
                Optional<Instant> sourceDate = sourceDate(item);
                yield sourceDate.isPresent()
                        && Duration.between(sourceDate.get(), Files.getLastModifiedTime(target).toInstant()).abs()
                        .compareTo(DATE_TOLERANCE) <= 0;
            }
            case PARTIAL_HASH -> {
                try (InputStream source = item.openInputStream(); InputStream written = Files.newInputStream(target)) {
                    yield Arrays.equals(partialSha256(source, size), partialSha256(written, size));
                }
            }
            case FULL_HASH -> {
                try (InputStream source = item.openInputStream(); InputStream written = Files.newInputStream(target)) {
                    yield Arrays.equals(sha256(source), sha256(written));
                }
            }
        };
    }

    /** The local file of the item, null when it is not a local file (e.g. a URL). */
    static Path localSource(WorkItem item) {
        return item.isLocal() || item.isTempFile() ? item.getLocalLocation() : null;
    }

    /** The size of the item: from its metadata, else from its local file, else by reading it. */
    static long sourceSize(WorkItem item) throws IOException {
        Long size = item.getMetadatas().getSize();
        if (size != null) {
            return size;
        }
        Path local = localSource(item);
        if (local != null) {
            return Files.size(local);
        }
        try (InputStream in = item.openInputStream()) {
            return in.transferTo(OutputStream.nullOutputStream());
        }
    }

    /** The modification date of the item: from its metadata, else from its local file; empty when unknown. */
    static Optional<Instant> sourceDate(WorkItem item) throws IOException {
        Optional<Instant> date = item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED);
        if (date.isPresent()) {
            return date;
        }
        Path local = localSource(item);
        return local == null ? Optional.empty() : Optional.of(Files.getLastModifiedTime(local).toInstant());
    }

    /** SHA-256 of the first and last {@value #PARTIAL_CHUNK} bytes, of everything up to twice that size. */
    static byte[] partialSha256(InputStream in, long size) throws IOException {
        if (size <= 2L * PARTIAL_CHUNK) {
            return sha256(in);
        }
        MessageDigest digest = newSha256();
        digest.update(in.readNBytes(PARTIAL_CHUNK));
        in.skipNBytes(size - 2L * PARTIAL_CHUNK);
        digest.update(in.readNBytes(PARTIAL_CHUNK));
        return digest.digest();
    }

    static byte[] sha256(InputStream in) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
