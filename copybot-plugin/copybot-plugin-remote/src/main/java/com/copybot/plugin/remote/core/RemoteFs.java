package com.copybot.plugin.remote.core;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One connection to a remote server (spec remote §3): the primitives each protocol implements, on which
 * {@link RemoteRead} and {@link RemoteWrite} build the rest. Paths use '/' between their names, as the action's
 * configuration and its output pattern give them (relative to the server's starting folder, or absolute; an S3 key).
 * <p>
 * A connection is used by one thread at a time ({@link RemotePool} hands it out), and runs one operation at a time:
 * the stream of {@link #open} is closed before the next call. No message of an {@link IOException} may hold a secret
 * (password, passphrase, secret key, signature).
 */
public interface RemoteFs extends AutoCloseable {

    /**
     * The entries of a folder, in no particular order, without "." and "..".
     *
     * @throws IOException the folder does not exist or cannot be read, or the connection failed
     */
    List<RemoteEntry> list(String folder) throws IOException;

    /**
     * The files of a folder's whole tree in one listing, for a protocol without real folders (S3: the keys under a
     * prefix, listed without delimiter, page by page): each file given as soon as known, named by its path relative
     * to the folder ('/' between the names), in the server's order. {@link RemoteRead} uses it for a recursive
     * listing when the protocol does not signal folders.
     *
     * @return false (the default): not supported, nothing listed; {@link #list} is used folder by folder
     */
    default boolean listTree(String folder, Consumer<RemoteEntry> files) throws IOException {
        return false;
    }

    /** Reads a file; the stream must be closed (also when it is not read to its end) before the next call. */
    InputStream open(String path) throws IOException;

    /**
     * The state of a path; empty when nothing exists there. The date is the one {@link #upload} kept when
     * {@link #keepsLastModified()} (S3: the {@code x-amz-meta-mtime} of the object, else its LastModified).
     */
    Optional<RemoteEntry> stat(String path) throws IOException;

    /** Creates a folder and the missing folders above it; nothing when it exists. */
    void mkdirs(String folder) throws IOException;

    /**
     * Writes a file from {@code content}, read to its end, replacing an existing one (the caller decided it).
     * <ul>
     *     <li>The content is read on the calling thread, and only during the call: the caller counts what is read
     *     (the progress) and stops a cancelled upload by failing a read (an {@link java.io.InterruptedIOException}),
     *     a failure the implementation lets through. A protocol sending from other threads first reads what it sends
     *     (S3: each part, or the whole file up to the multipart threshold, into one buffer of the connection, reused
     *     from part to part and from upload to upload, its slices sent without copy).</li>
     *     <li>When it throws, the connection must still be usable to {@link #delete} what was sent (FTP: abort the
     *     transfer and complete the pending command; S3: abort the multipart upload); when it cannot be, the
     *     implementation throws from the next call, and the caller closes it.</li>
     *     <li>When {@link #keepsLastModified()}, the file gets {@code lastModified} as its date of modification
     *     (SFTP: {@code setMtime} after the transfer; FTP: {@code MFMT}, else {@code SITE UTIME}; S3: the
     *     {@code x-amz-meta-mtime} metadata, given back by {@link #stat}: an S3 listing gives LastModified only, the
     *     date of the upload).</li>
     * </ul>
     *
     * @param size         the number of bytes expected, -1 when unknown
     * @param lastModified the date to give the file, null: the server's
     */
    void upload(String path, InputStream content, long size, Instant lastModified) throws IOException;

    /**
     * Whether {@link #upload} gives the file the date it is told, {@link #stat} giving it back: false (the default),
     * the date of a written file is the server's, and {@link RemoteWrite} compares sizes only.
     */
    default boolean keepsLastModified() {
        return false;
    }

    /**
     * Renames a file, keeping its date.
     *
     * @param replace an existing {@code to} is replaced: atomically where the protocol can (SFTP:
     *                {@code posix-rename@openssh.com}), else by deleting it first (FTP, SFTP without the extension);
     *                false: an existing {@code to} makes the rename fail
     */
    void rename(String from, String to, boolean replace) throws IOException;

    /** Deletes a file; nothing when it does not exist. */
    void delete(String path) throws IOException;

    /**
     * Whether {@link #upload} makes the file appear whole or not at all (S3): {@link RemoteWrite} then writes under
     * the final name, without temporary file and rename.
     */
    default boolean atomicUpload() {
        return false;
    }

    /**
     * Whether this connection, kept idle by the {@link RemotePool}, can still be used (e.g. the server did not close
     * it): a cheap check, local when it can be (JSch's {@code isConnected}), or true when the protocol cannot tell.
     * False: the pool closes it and connects again. Called outside the pool's lock.
     */
    default boolean isUsable() {
        return true;
    }

    /** Closes the connection; never throws. */
    @Override
    void close();
}
