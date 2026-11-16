package com.copybot.plugin.ffmpeg.ffmpeg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The download of ffmpeg from a fake release on a local server: never the network. */
public class FfmpegDownloaderTest {

    @TempDir
    Path tempDir;

    private FakeRelease release;

    @AfterEach
    void stop() {
        if (release != null) {
            release.close();
        }
    }

    private Path root() {
        return tempDir.resolve("ffmpeg");
    }

    private FfmpegDownloader downloader() {
        return new FfmpegDownloader(release.release(), Duration.ofSeconds(10), Duration.ofSeconds(10));
    }

    /** What is left in the folder of the downloaded copies. */
    private List<String> left() throws Exception {
        if (!Files.exists(root())) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(root())) {
            return paths.map(p -> p.getFileName().toString()).toList();
        }
    }

    private String failure(FfmpegDownloader downloader) {
        FfmpegUnavailableException e = assertThrows(FfmpegUnavailableException.class,
                () -> downloader.install(root(), (received, total) -> {
                }));
        return e.text().key();
    }

    @Test
    public void theGplBuildOfTheHighestStableBranchIsChosenFromTheChecksums() throws Exception {
        String checksums = "a".repeat(64) + "  ffmpeg-master-latest-win64-gpl.zip\n"
                + "b".repeat(64) + "  ffmpeg-n8.1-latest-win64-gpl-8.1.zip\n"
                + "c".repeat(64) + "  ffmpeg-n8.1-latest-win64-gpl-shared-8.1.zip\n"
                + "D".repeat(64) + " *ffmpeg-n9.0-latest-win64-gpl-9.0.zip\r\n"
                + "e".repeat(64) + "  ffmpeg-n9.0-latest-win64-lgpl-9.0.zip\n"
                + "f".repeat(64) + "  ffmpeg-n10.0-latest-winarm64-gpl-10.0.zip\n"
                + "f".repeat(64) + "  ffmpeg-n10.0-latest-linux64-gpl-10.0.tar.xz\n";

        FfmpegDownloader.Archive archive = FfmpegDownloader.select(checksums).orElseThrow();

        assertEquals("ffmpeg-n9.0-latest-win64-gpl-9.0.zip", archive.name());
        assertEquals("9.0", archive.version());
        assertEquals("d".repeat(64), archive.sha256());
        assertTrue(FfmpegDownloader.select("b".repeat(64) + "  ffmpeg-n8.1-latest-win64-gpl-8.1.zip\n"
                + "c".repeat(64) + "  ffmpeg-n7.1-latest-win64-gpl-7.1.zip\n").orElseThrow().version().equals("8.1"));
        assertTrue(FfmpegDownloader.select("a".repeat(64) + "  ffmpeg-master-latest-win64-gpl.zip\n").isEmpty());
        assertTrue(FfmpegDownloader.select("xyz  ffmpeg-n8.1-latest-win64-gpl-8.1.zip\n").isEmpty(),
                "a line without a SHA-256 is not taken");
    }

    @Test
    public void theArchiveIsCheckedThenInstalledUnderItsVersion() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        List<long[]> progress = new ArrayList<>();

        Path executable = downloader().install(root(), (received, total) -> progress.add(new long[]{received, total}));

        assertEquals(root().resolve("8.1").resolve("bin").resolve("ffmpeg.exe"), executable);
        assertEquals("not really ffmpeg", Files.readString(executable));
        assertEquals("GPL v3", Files.readString(root().resolve("8.1").resolve("LICENSE.txt")));
        assertEquals(List.of("8.1"), left(), "nothing else is left");
        assertFalse(progress.isEmpty());
        long size = FakeRelease.build().length;
        assertArrayEquals(new long[]{size, size}, progress.getLast());
    }

    @Test
    public void anInstalledVersionIsNotDownloadedAgain() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        downloader().install(root(), (received, total) -> {
        });

        Path executable = downloader().install(root(), (received, total) -> {
        });

        assertEquals(root().resolve("8.1").resolve("bin").resolve("ffmpeg.exe"), executable);
        assertEquals(1, release.requests(FakeRelease.ARCHIVE));
    }

    @Test
    public void theRedirectToTheFileStorageIsFollowed() throws Exception {
        byte[] archive = FakeRelease.build();
        release = new FakeRelease().release(archive)
                .redirect(FakeRelease.ARCHIVE, "/storage/archive.zip")
                .at("/storage/archive.zip", archive);

        Path executable = downloader().install(root(), (received, total) -> {
        });

        assertTrue(Files.isRegularFile(executable));
    }

    @Test
    public void aWrongFingerprintInstallsNothing() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        release.file(FakeRelease.ARCHIVE, FakeRelease.zip(Map.of("x/bin/ffmpeg.exe", "tampered")));

        assertEquals("ffmpeg.download.error.digest", failure(downloader()));
        assertEquals(List.of(), left());
    }

    @Test
    public void anEntryLeavingTheFolderIsRefusedAndNothingIsInstalled() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ffmpeg/bin/ffmpeg.exe", "ffmpeg");
        entries.put("ffmpeg/../../evil.txt", "zip slip");
        release = new FakeRelease().release(FakeRelease.zip(entries));

        assertEquals("ffmpeg.download.error.archive", failure(downloader()));
        assertEquals(List.of(), left());
        assertFalse(Files.exists(tempDir.resolve("evil.txt")));
    }

    @Test
    public void anAbsoluteEntryIsRefused() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ffmpeg/bin/ffmpeg.exe", "ffmpeg");
        entries.put(tempDir.resolve("evil.txt").toString().replace('\\', '/'), "absolute");
        release = new FakeRelease().release(FakeRelease.zip(entries));

        assertEquals("ffmpeg.download.error.archive", failure(downloader()));
        assertEquals(List.of(), left());
        assertFalse(Files.exists(tempDir.resolve("evil.txt")));
    }

    @Test
    public void anArchiveWithoutFfmpegInstallsNothing() throws Exception {
        release = new FakeRelease().release(FakeRelease.zip(Map.of("ffmpeg/bin/ffprobe.exe", "ffprobe")));

        assertEquals("ffmpeg.download.error.archive", failure(downloader()));
        assertEquals(List.of(), left());
    }

    @Test
    public void aDamagedArchiveInstallsNothing() throws Exception {
        release = new FakeRelease().release("not a zip at all".getBytes());

        assertEquals("ffmpeg.download.error.archive", failure(downloader()));
        assertEquals(List.of(), left());
    }

    @Test
    public void aReleaseWithoutTheArchiveIsAClearFailure() throws Exception {
        release = new FakeRelease().checksums("a".repeat(64) + "  ffmpeg-master-latest-win64-gpl.zip\n");

        assertEquals("ffmpeg.download.error.no-archive", failure(downloader()));
        assertEquals(List.of(), left());
    }

    @Test
    public void anHttpErrorIsAClearFailure() throws Exception {
        release = new FakeRelease(); // nothing served: 404

        assertEquals("ffmpeg.download.error.http", failure(downloader()));
        assertEquals(List.of(), left());
    }

    @Test
    public void anUnreachableServerIsAClearFailure() throws Exception {
        release = new FakeRelease();
        FfmpegDownloader downloader = downloader();
        release.close();

        assertEquals("ffmpeg.download.error.network", failure(downloader));
        assertEquals(List.of(), left());
    }

    @Test
    public void aCancelDuringTheDownloadLeavesNothing() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());

        assertThrows(InterruptedException.class, () -> downloader().install(root(), (received, total) ->
                Thread.currentThread().interrupt())); // the cancel comes with the first bytes

        assertEquals(List.of(), left());
        Thread.interrupted();
    }

    @Test
    public void aCancelDuringTheExtractionIsACancelAndLeavesNothing() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        FfmpegDownloader downloader = downloader();
        downloader.beforeExtract = () -> Thread.currentThread().interrupt(); // checked, the cancel comes now

        assertThrows(InterruptedException.class, () -> downloader.install(root(), (received, total) -> {
        }));

        assertFalse(Thread.currentThread().isInterrupted(), "cleared, as by any InterruptedException");
        assertEquals(List.of(), left());
    }

    @Test
    public void aFileClosedByACancelIsACancelNotAFailure() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        FfmpegDownloader downloader = downloader();
        downloader.beforeExtract = () -> {
            // what a write of the extraction meets when the thread is interrupted
            throw new UncheckedIOException(new ClosedByInterruptException());
        };

        assertThrows(InterruptedException.class, () -> downloader.install(root(), (received, total) -> {
        }));

        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(List.of(), left());
    }

    @Test
    public void aLocalFailureCausedByACancelIsACancel() throws Exception {
        InterruptedException closed = assertThrows(InterruptedException.class,
                () -> FfmpegDownloader.rethrowCancel(new ClosedByInterruptException()));
        assertInstanceOf(ClosedByInterruptException.class, closed.getCause());
        assertFalse(Thread.currentThread().isInterrupted());

        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> FfmpegDownloader.rethrowCancel(new IOException("closed")));
        assertFalse(Thread.currentThread().isInterrupted());

        FfmpegDownloader.rethrowCancel(new IOException("disk full")); // not a cancel: nothing thrown
    }

    @Test
    public void tooLittleFreeSpaceIsAClearFailureBeforeTheDownload() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        FfmpegDownloader downloader = downloader();
        downloader.requiredSpace = Long.MAX_VALUE;

        FfmpegUnavailableException e = assertThrows(FfmpegUnavailableException.class,
                () -> downloader.install(root(), (received, total) -> {
                }));

        assertEquals("ffmpeg.download.error.space", e.text().key());
        assertEquals(root().toString(), e.text().args().getFirst());
        assertEquals(0, release.requests(FakeRelease.ARCHIVE));
        assertEquals(List.of(), left());
    }

    @Test
    public void aCancelWhileTheServerHangsEndsTheDownloadAndLeavesNothing() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        release.hang = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                downloader().install(root(), (received, total) -> started.countDown());
            } catch (Throwable e) {
                outcome.set(e);
            }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(thread.isAlive(), "the cancel ends the blocked download");
        assertInstanceOf(InterruptedException.class, outcome.get());
        assertEquals(List.of(), left());
    }

    @Test
    public void aStalledDownloadFailsAndLeavesNothing() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        release.hang = new CountDownLatch(1);
        FfmpegDownloader downloader = new FfmpegDownloader(release.release(), Duration.ofSeconds(10),
                Duration.ofMillis(500));

        assertEquals("ffmpeg.download.error.stalled",
                CompletableFuture.supplyAsync(() -> failure(downloader)).get(20, TimeUnit.SECONDS));
        assertEquals(List.of(), left());
    }

    @Test
    public void staleDownloadFoldersAreRemoved() throws Exception {
        release = new FakeRelease().release(FakeRelease.build());
        Path stale = Files.createDirectories(root().resolve(".download-old"));
        Files.writeString(stale.resolve("archive.zip"), "partial");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        downloader().install(root(), (received, total) -> {
        });

        assertEquals(List.of("8.1"), left());
    }
}
