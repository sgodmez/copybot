package com.copybot.plugin.ffmpeg.ffmpeg;

import com.copybot.plugin.exec.process.WorkFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Downloads ffmpeg for Windows x64 (spec exec-ffmpeg §4): the GPL build of BtbN/FFmpeg-Builds, release "latest",
 * of the highest stable branch it holds (ffmpeg-n&lt;x.y&gt;-latest-win64-gpl-&lt;x.y&gt;.zip). Its SHA-256 is read
 * from the checksums.sha256 of the release and checked before anything is extracted. The archive is extracted in a
 * temporary folder next to the downloaded copies, then moved at once to {@code <installRoot>/<x.y>}: a failure or a
 * cancel leaves nothing behind.
 * <p>
 * Cancel: an interruption of the calling thread ends the download, even a blocked one.
 */
public final class FfmpegDownloader {

    /** The release of the builds; checked on 2026-10-31 (the stable branches were then n8.1 and n9.0). */
    public static final URI RELEASE = URI.create("https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/");
    /** The fingerprints of the release: "&lt;sha256&gt;  &lt;file name&gt;" per line (sha256sum). */
    public static final String CHECKSUMS = "checksums.sha256";
    /**
     * The GPL build of a stable branch for Windows x64, not the "shared" one, nor the development one (master). The
     * highest nX.Y listed is chosen, with no upper bound: BtbN keeps only its last stable branches (n8.1 and n9.0 on
     * 2026-10-31, n7.1 no longer), a fixed one would vanish from the release.
     */
    static final Pattern ARCHIVE = Pattern.compile("ffmpeg-n(\\d{1,4})\\.(\\d{1,4})-latest-win64-gpl-\\1\\.\\2\\.zip");
    /** Prefix of the temporary folders of a download, next to the installed versions. */
    static final String TEMP_PREFIX = ".download-";

    private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-fA-F]{64}) [ *](\\S+)");
    /** The archive is about 200 MB: larger is not this archive. */
    private static final long MAX_ARCHIVE = 1L << 30;
    /** Its content, extracted: about 600 MB. */
    private static final long MAX_EXTRACTED = 4L << 30;
    private static final long MAX_CHECKSUMS = 1L << 20;
    /** The free space a download needs: the archive and its content, side by side for a moment. */
    static final long REQUIRED_SPACE = 1L << 30;
    /** A move refused (an antivirus reading the new ffmpeg.exe) is tried again this many times. */
    private static final int MOVE_TRIES = 5;
    private static final Duration MOVE_PAUSE = Duration.ofMillis(200);
    private static final Duration STALE = Duration.ofDays(1);
    private static final String EXECUTABLE = "ffmpeg.exe";
    private static final System.Logger LOGGER = System.getLogger(FfmpegDownloader.class.getName());

    /**
     * The archive chosen in the release.
     *
     * @param name    its file name
     * @param version its branch ("8.1"): the folder it is installed in
     * @param sha256  its fingerprint, in lower case
     */
    public record Archive(String name, String version, String sha256) {
    }

    /** Receives the progress of the download of the archive. */
    @FunctionalInterface
    public interface Progress {
        /**
         * @param bytes the bytes received so far
         * @param total the size of the archive; -1 when unknown
         */
        void received(long bytes, long total);
    }

    private final URI release;
    private final Duration headersTimeout;
    private final Duration stallTimeout;
    /** The free space needed in the folder of the copies; the tests change it. */
    volatile long requiredSpace = REQUIRED_SPACE;
    /** Run between the check of the archive and its extraction; for the tests (a cancel at that point). */
    volatile Runnable beforeExtract = () -> {
    };

    /** The real release. */
    public FfmpegDownloader() {
        this(RELEASE, Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    /**
     * @param release        the folder of the release files (a fake server in the tests), ending with /
     * @param headersTimeout the wait for the connection and the headers of an answer
     * @param stallTimeout   the download fails when nothing is received for this long
     */
    public FfmpegDownloader(URI release, Duration headersTimeout, Duration stallTimeout) {
        this.release = release;
        this.headersTimeout = headersTimeout;
        this.stallTimeout = stallTimeout;
    }

    /**
     * The archive of the highest stable branch listed in checksums.sha256; empty when there is none.
     */
    static Optional<Archive> select(String checksums) {
        Archive best = null;
        for (String line : checksums.split("\\R")) {
            Matcher matcher = CHECKSUM_LINE.matcher(line.strip());
            if (!matcher.matches()) {
                continue;
            }
            Matcher archive = ARCHIVE.matcher(matcher.group(2));
            if (!archive.matches()) {
                continue;
            }
            Archive candidate = new Archive(matcher.group(2), archive.group(1) + "." + archive.group(2),
                    matcher.group(1).toLowerCase(Locale.ROOT));
            if (best == null || FfmpegLocator.compareVersions(candidate.version(), best.version()) > 0) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Downloads, checks and installs ffmpeg, unless the version of the release is installed already.
     *
     * @param installRoot the folder of the downloaded copies (created when needed)
     * @return {@code <installRoot>/<version>/bin/ffmpeg.exe}
     * @throws FfmpegUnavailableException it failed: nothing was installed
     * @throws InterruptedException       the calling thread was interrupted (a cancel): nothing was installed
     */
    public Path install(Path installRoot, Progress progress) throws FfmpegUnavailableException, InterruptedException {
        removeStaleDownloads(installRoot);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(headersTimeout).build();
        try {
            ByteArrayOutputStream listing = new ByteArrayOutputStream();
            fetch(client, CHECKSUMS, listing, MAX_CHECKSUMS, null);
            Archive archive = select(listing.toString(StandardCharsets.UTF_8))
                    .orElseThrow(() -> new FfmpegUnavailableException(
                            FfmpegText.of("ffmpeg.download.error.no-archive")));
            Path target = installRoot.resolve(archive.version());
            Path executable = target.resolve("bin").resolve(EXECUTABLE);
            if (Files.isRegularFile(executable)) {
                return executable;
            }
            LOGGER.log(System.Logger.Level.INFO, "Downloading {0}", archive.name());
            Path temp = null;
            try {
                Files.createDirectories(installRoot);
                long usable = Files.getFileStore(installRoot).getUsableSpace();
                if (usable < requiredSpace) {
                    throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.space", installRoot,
                            requiredSpace >> 20, usable >> 20));
                }
                temp = Files.createTempDirectory(installRoot, TEMP_PREFIX);
                Path zip = temp.resolve("archive.zip");
                MessageDigest sha = sha256();
                try (OutputStream out = new DigestOutputStream(Files.newOutputStream(zip), sha)) {
                    fetch(client, archive.name(), out, MAX_ARCHIVE, progress);
                }
                if (!HexFormat.of().formatHex(sha.digest()).equals(archive.sha256())) {
                    throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.digest",
                            archive.name()));
                }
                beforeExtract.run();
                Path extracted = temp.resolve("content");
                extract(zip, extracted, archive.name());
                Path home = home(extracted).orElseThrow(() -> new FfmpegUnavailableException(FfmpegText.of(
                        "ffmpeg.download.error.archive", archive.name(), "no bin/" + EXECUTABLE)));
                checkCancel();
                if (Files.isRegularFile(executable)) {
                    return executable; // installed meanwhile by another Copybot
                }
                if (Files.exists(target)) {
                    WorkFolder.deleteTree(target); // a version folder without its executable: left by hand
                }
                move(home, target, executable);
                LOGGER.log(System.Logger.Level.INFO, "ffmpeg {0} installed in {1}", archive.version(), target);
                return executable;
            } catch (IOException | UncheckedIOException e) {
                Throwable cause = e instanceof UncheckedIOException u ? u.getCause() : e;
                rethrowCancel(cause);
                throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.io", installRoot,
                        describe(cause)), e);
            } finally {
                if (temp != null) {
                    WorkFolder.deleteTree(temp);
                }
            }
        } finally {
            client.shutdownNow();
        }
    }

    /**
     * Downloads a file of the release into {@code out}.
     *
     * @param progress null: none
     * @throws UncheckedIOException writing to {@code out} failed
     */
    private void fetch(HttpClient client, String name, OutputStream out, long max, Progress progress)
            throws FfmpegUnavailableException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(release.resolve(name)).timeout(headersTimeout)
                .header("User-Agent", "Copybot").GET().build();
        AtomicBoolean stalled = new AtomicBoolean();
        AtomicLong lastByte = new AtomicLong(System.nanoTime());
        Timer watchdog = new Timer("ffmpeg-download-watchdog", true);
        try {
            checkCancel();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.http",
                            response.statusCode(), name));
                }
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (total > max) {
                    throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.too-large", name));
                }
                lastByte.set(System.nanoTime());
                watchdog.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (System.nanoTime() - lastByte.get() > stallTimeout.toNanos()
                                && stalled.compareAndSet(false, true)) {
                            try {
                                in.close(); // ends the blocked read
                            } catch (IOException e) {
                                // the read fails anyway
                            }
                        }
                    }
                }, 250, 250);
                long received = 0;
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    lastByte.set(System.nanoTime());
                    checkCancel();
                    received += n;
                    if (received > max) {
                        throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.too-large", name));
                    }
                    write(out, buffer, n);
                    if (progress != null) {
                        progress.received(received, total);
                    }
                    checkCancel();
                }
                checkCancel();
                if (stalled.get()) {
                    throw stalled();
                }
                if (total >= 0 && received != total) {
                    throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.network",
                            name + ": " + received + " / " + total + " bytes"));
                }
            }
        } catch (IOException e) {
            rethrowCancel(e); // a cancel during a blocked read ends it with an IOException
            if (stalled.get()) {
                throw stalled();
            }
            throw new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.network", describe(e)), e);
        } finally {
            watchdog.cancel();
        }
    }

    private FfmpegUnavailableException stalled() {
        return new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.stalled",
                stallTimeout.toSeconds()));
    }

    private static void write(OutputStream out, byte[] buffer, int length) {
        try {
            out.write(buffer, 0, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a local failure, not a network one
        }
    }

    /**
     * Moves the extracted folder to its version folder at once. Refused (on Windows, an antivirus reading the new
     * ffmpeg.exe): tried again a few times.
     */
    private static void move(Path home, Path target, Path executable) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(home, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AccessDeniedException e) {
                if (Files.isRegularFile(executable)) {
                    return; // installed meanwhile by another Copybot
                }
                if (attempt >= MOVE_TRIES) {
                    throw e;
                }
                Thread.sleep(MOVE_PAUSE);
            } catch (IOException e) {
                if (!Files.isRegularFile(executable)) { // else installed meanwhile by another Copybot
                    throw e;
                }
                return;
            }
        }
    }

    /**
     * A local failure caused by a cancel (an interruption closing a file: {@link ClosedByInterruptException}, or
     * the interrupt status set) is a cancel, never a failure kept for the run.
     */
    static void rethrowCancel(Throwable failure) throws InterruptedException {
        if (failure instanceof ClosedByInterruptException || Thread.interrupted()) {
            Thread.interrupted(); // a ClosedByInterruptException sets it
            InterruptedException cancel = new InterruptedException("ffmpeg download cancelled");
            cancel.initCause(failure);
            throw cancel;
        }
    }

    /** An interrupted thread (a cancel) throws, its interrupt status cleared as for any InterruptedException. */
    private static void checkCancel() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("ffmpeg download cancelled");
        }
    }

    /**
     * Extracts the archive into the folder. An entry that would leave the folder (zip slip: "..", an absolute path)
     * refuses the whole archive.
     */
    static void extract(Path zip, Path into, String archiveName)
            throws IOException, InterruptedException, FfmpegUnavailableException {
        Path root = Files.createDirectories(into).toAbsolutePath().normalize();
        long extracted = 0;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : Collections.list(file.entries())) {
                checkCancel();
                Path target;
                try {
                    target = root.resolve(entry.getName()).normalize();
                } catch (InvalidPathException e) {
                    throw invalid(archiveName, "invalid entry name " + entry.getName());
                }
                if (target.equals(root)) {
                    continue;
                }
                if (!target.startsWith(root)) {
                    throw invalid(archiveName, "entry outside the folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        extracted += n;
                        if (extracted > MAX_EXTRACTED) {
                            throw invalid(archiveName, "content too large");
                        }
                        out.write(buffer, 0, n);
                        checkCancel();
                    }
                } catch (FileAlreadyExistsException e) {
                    throw invalid(archiveName, "duplicate entry " + entry.getName());
                }
            }
        } catch (ZipException e) {
            throw invalid(archiveName, describe(e));
        }
    }

    private static FfmpegUnavailableException invalid(String archiveName, String reason) {
        return new FfmpegUnavailableException(FfmpegText.of("ffmpeg.download.error.archive", archiveName, reason));
    }

    /** The folder holding bin/ffmpeg.exe: the extracted folder, or its single top folder (a BtbN build). */
    private static Optional<Path> home(Path extracted) throws IOException {
        if (Files.isRegularFile(extracted.resolve("bin").resolve(EXECUTABLE))) {
            return Optional.of(extracted);
        }
        try (Stream<Path> children = Files.list(extracted)) {
            List<Path> folders = children.filter(Files::isDirectory).toList();
            return folders.size() == 1 && Files.isRegularFile(folders.getFirst().resolve("bin").resolve(EXECUTABLE))
                    ? Optional.of(folders.getFirst()) : Optional.empty();
        }
    }

    /** Removes the temporary folders of downloads older than a day (Copybot stopped during a download). */
    private static void removeStaleDownloads(Path installRoot) {
        if (!Files.isDirectory(installRoot)) {
            return;
        }
        Instant limit = Instant.now().minus(STALE);
        try (Stream<Path> children = Files.list(installRoot)) {
            children.filter(child -> child.getFileName().toString().startsWith(TEMP_PREFIX))
                    .filter(child -> {
                        try {
                            return Files.getLastModifiedTime(child).toInstant().isBefore(limit);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(WorkFolder::deleteTree);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.DEBUG, "Listing " + installRoot + " failed", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-256
        }
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
