package com.copybot.plugin.exec.ffmpeg;

import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.exec.process.FakeProgram;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.copybot.plugin.exec.process.WorkFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding ffmpeg once per run (spec exec-ffmpeg §4) with fake executables: a file named ffmpeg.exe holding the
 * version it gives, run as {@link FakeProgram} by the launcher of the context.
 */
public class FfmpegSetupTest {

    @TempDir
    Path tempDir;

    private FakeRelease release;
    private final AtomicInteger launches = new AtomicInteger();

    @AfterEach
    void stop() {
        if (release != null) {
            release.close();
        }
    }

    private Path pathFolder() {
        return tempDir.resolve("path");
    }

    private Path installRoot() {
        return tempDir.resolve("data").resolve("ffmpeg");
    }

    /** A fake ffmpeg.exe giving this version. */
    private Path fake(Path file, String version) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, version);
    }

    private FfmpegSetup.Context context(boolean downloadSupported) {
        // Windows rules (PATHEXT, no executable bit) on every system: the fakes are named ffmpeg.exe
        ProgramResolver.Environment environment = new ProgramResolver.Environment(pathFolder().toString(), ".EXE",
                true);
        FfmpegDownloader downloader = release == null ? null
                : new FfmpegDownloader(release.release(), Duration.ofSeconds(10), Duration.ofSeconds(10));
        return new FfmpegSetup.Context(environment, installRoot(), downloadSupported, downloader, executable -> {
            launches.incrementAndGet();
            try {
                return FakeProgram.command("ffmpeg", Files.readString(executable));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static FfmpegSettings settings(String path, Boolean download) {
        return FfmpegSettings.of(path, download);
    }

    private static Ffmpeg get(FfmpegSetup setup) throws Exception {
        return setup.get(ItemProgress.NONE, "Downloading ffmpeg");
    }

    private static String failure(FfmpegSetup setup) {
        return assertThrows(FfmpegUnavailableException.class, () -> get(setup)).text().key();
    }

    @Test
    public void thePathOfTheActionComesFirstThenTheDownloadedCopyThenThePath() throws Exception {
        Path explicit = fake(tempDir.resolve("mine").resolve("ffmpeg.exe"), "n6.0-mine");
        fake(installRoot().resolve("8.1").resolve("bin").resolve("ffmpeg.exe"), "n8.1-downloaded");
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup.Context context = context(true);

        Ffmpeg mine = get(FfmpegSetup.unshared(settings(explicit.toString(), false), context));
        assertEquals(explicit.toAbsolutePath(), mine.executable());
        assertEquals("n6.0-mine", mine.version().text());
        assertEquals(6, mine.version().major());

        assertEquals("n8.1-downloaded", get(FfmpegSetup.unshared(settings(null, false), context)).version().text());
        assertEquals("n8.1-downloaded", get(FfmpegSetup.unshared(settings("  ", false), context)).version().text(),
                "a blank path is no path");

        WorkFolder.deleteTree(installRoot());
        assertEquals("n7.1-path", get(FfmpegSetup.unshared(settings(null, false), context)).version().text());
    }

    @Test
    public void theCommandRunsTheExecutableThroughTheLauncher() throws Exception {
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");

        Ffmpeg ffmpeg = get(FfmpegSetup.unshared(settings(null, false), context(false)));

        List<String> command = ffmpeg.command(List.of("-version"));
        assertEquals(FakeProgram.command("ffmpeg", "n7.1-path", "-version"), command);
    }

    @Test
    public void theHighestDownloadedVersionIsTakenAndUnfinishedDownloadsAreIgnored() throws Exception {
        fake(installRoot().resolve("7.1").resolve("bin").resolve("ffmpeg.exe"), "n7.1");
        fake(installRoot().resolve("10.0").resolve("bin").resolve("ffmpeg.exe"), "n10.0");
        fake(installRoot().resolve("9.0").resolve("bin").resolve("ffmpeg.exe"), "n9.0");
        Files.createDirectories(installRoot().resolve("11.0").resolve("bin")); // no executable in it
        fake(installRoot().resolve(".download-x").resolve("x").resolve("bin").resolve("ffmpeg.exe"), "n12.0");

        assertEquals("n10.0", get(FfmpegSetup.unshared(settings(null, false), context(false))).version().text());
    }

    @Test
    public void aPathOfTheActionThatIsNotFoundIsAnErrorEvenWithAnotherFfmpeg() throws Exception {
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");

        FfmpegSetup setup = FfmpegSetup.unshared(settings(tempDir.resolve("nowhere/ffmpeg.exe").toString(), true),
                context(true));

        assertEquals("ffmpeg.path-not-found", failure(setup));
    }

    @Test
    public void theVersionIsReadOnceForEveryStepAndItemOfTheRun() throws Exception {
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup.Context context = context(false);
        FfmpegSetup first = FfmpegSetup.acquire(settings(null, false), context);
        FfmpegSetup second = FfmpegSetup.acquire(settings(" ", null), context);
        try {
            assertSame(first, second, "two ffmpeg steps of a run share it");
            List<Future<Ffmpeg>> found = new ArrayList<>();
            try (ExecutorService items = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 8; i++) {
                    FfmpegSetup setup = i % 2 == 0 ? first : second;
                    found.add(items.submit(() -> get(setup)));
                }
            }
            for (Future<Ffmpeg> ffmpeg : found) {
                assertEquals("n7.1-path", ffmpeg.get().version().text());
            }
            assertEquals(1, launches.get());
        } finally {
            first.release();
            second.release();
        }

        FfmpegSetup next = FfmpegSetup.acquire(settings(null, false), context);
        try {
            assertNotSame(first, next, "the next run looks again");
            get(next);
            assertEquals(2, launches.get());
        } finally {
            next.release();
        }
    }

    @Test
    public void stepsWithOtherSettingsDoNotShare() throws Exception {
        FfmpegSetup.Context context = context(false);
        FfmpegSetup first = FfmpegSetup.acquire(settings(null, false), context);
        FfmpegSetup other = FfmpegSetup.acquire(settings("C:/ffmpeg/ffmpeg.exe", false), context);
        try {
            assertNotSame(first, other);
        } finally {
            first.release();
            other.release();
        }
    }

    @Test
    public void notFoundWithoutTheDownloadTellsWhatToDo() throws Exception {
        assertEquals("ffmpeg.not-found.download", failure(FfmpegSetup.unshared(settings(null, false),
                context(true))));
        assertEquals("ffmpeg.not-found.install", failure(FfmpegSetup.unshared(settings(null, true),
                context(false))), "no download outside Windows x64");
        assertEquals("ffmpeg.not-found.install", failure(FfmpegSetup.unshared(settings(null, false),
                context(false))));
    }

    @Test
    public void aNotFoundFfmpegIsLookedForAgainByTheNextItem() throws Exception {
        FfmpegSetup setup = FfmpegSetup.unshared(settings(null, false), context(true));
        assertEquals("ffmpeg.not-found.download", failure(setup));
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");

        assertEquals("n7.1-path", get(setup).version().text(), "the lookup is not kept as a failure");
    }

    @Test
    public void aStepWithoutTheDownloadFindsTheCopyAnotherStepOfThePhaseDownloaded() throws Exception {
        release = new FakeRelease().release(build("n8.1-downloaded"));
        FfmpegSetup.Context context = context(true);
        FfmpegSetup without = FfmpegSetup.acquire(settings(null, false), context);
        FfmpegSetup with = FfmpegSetup.acquire(settings(null, true), context);
        try {
            assertEquals("ffmpeg.not-found.download", failure(without), "nothing downloaded yet");

            assertEquals("n8.1-downloaded", get(with).version().text());

            Ffmpeg found = get(without);
            assertEquals("n8.1-downloaded", found.version().text());
            assertEquals(installRoot().resolve("8.1").resolve("bin").resolve("ffmpeg.exe"), found.executable());
            assertEquals(1, release.requests(FakeRelease.ARCHIVE));
        } finally {
            without.release();
            with.release();
        }
    }

    @Test
    public void anFfmpegFoundButBrokenIsKeptForThePhase() throws Exception {
        Path ffmpeg = fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup.Context fake = context(false);
        AtomicInteger runs = new AtomicInteger();
        FfmpegSetup.Context broken = new FfmpegSetup.Context(fake.environment(), fake.installRoot(), false, null,
                executable -> {
                    runs.incrementAndGet();
                    return FakeProgram.command("exit", "1", "cannot run");
                });
        FfmpegSetup setup = FfmpegSetup.unshared(settings(null, false), broken);

        assertEquals("ffmpeg.error.version", failure(setup));
        assertEquals("ffmpeg.error.version", failure(setup), "not run again in the same phase");
        assertEquals(1, runs.get());
        assertTrue(Files.exists(ffmpeg));
    }

    @Test
    public void anFfmpegThatDoesNotGiveItsVersionIsAnError() throws Exception {
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup.Context fake = context(false);
        FfmpegSetup.Context broken = new FfmpegSetup.Context(fake.environment(), fake.installRoot(), false, null,
                executable -> FakeProgram.command("exit", "1", "cannot run"));

        assertEquals("ffmpeg.error.version", failure(FfmpegSetup.unshared(settings(null, false), broken)));
    }

    @Test
    public void anFfmpegThatCannotStartIsAnError() throws Exception {
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup.Context fake = context(false);
        FfmpegSetup.Context broken = new FfmpegSetup.Context(fake.environment(), fake.installRoot(), false, null,
                executable -> List.of(tempDir.resolve("missing-program.exe").toString()));

        assertEquals("ffmpeg.error.start", failure(FfmpegSetup.unshared(settings(null, false), broken)));
    }

    @Test
    public void anInterruptedCallerKeepsNothingAndTheNextOneLooks() throws Exception {
        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup setup = FfmpegSetup.unshared(settings(null, false), context(false));

        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> get(setup));
        assertFalse(Thread.currentThread().isInterrupted());

        assertEquals("n7.1-path", get(setup).version().text());
    }

    private static byte[] build(String version) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ffmpeg-n8.1-latest-win64-gpl-8.1/bin/ffmpeg.exe", version);
        return FakeRelease.zip(entries);
    }

    @Test
    public void aMissingFfmpegIsDownloadedOnceForAllTheItemsWhoseStatusShowsTheProgress() throws Exception {
        release = new FakeRelease().release(build("n8.1-downloaded"));
        FfmpegSetup.Context context = context(true);
        FfmpegSetup first = FfmpegSetup.acquire(settings(null, true), context);
        FfmpegSetup second = FfmpegSetup.acquire(settings(null, true), context);
        List<WorkStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<Ffmpeg>> found = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService items = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 6; i++) {
                    FfmpegSetup setup = i % 2 == 0 ? first : second;
                    found.add(items.submit(() -> {
                        start.await();
                        return setup.get(statuses::add, "Downloading ffmpeg");
                    }));
                }
                start.countDown();
            }
            for (Future<Ffmpeg> ffmpeg : found) {
                assertEquals("n8.1-downloaded", ffmpeg.get(30, TimeUnit.SECONDS).version().text());
                assertEquals(installRoot().resolve("8.1").resolve("bin").resolve("ffmpeg.exe"),
                        ffmpeg.get().executable());
            }
        } finally {
            first.release();
            second.release();
        }
        assertEquals(1, release.requests(FakeRelease.ARCHIVE), "downloaded once");
        assertEquals(1, release.requests("checksums.sha256"));
        assertEquals(1, launches.get());
        assertFalse(statuses.isEmpty());
        assertTrue(statuses.stream().allMatch(s -> "Downloading ffmpeg".equals(s.actionName())), "" + statuses);
        assertEquals(100, statuses.getLast().actionPercent());
    }

    /** Calls get on a thread of its own; what it got (an Ffmpeg or a Throwable) in the reference. */
    private static Thread item(FfmpegSetup setup, ItemProgress progress, AtomicReference<Object> outcome) {
        return Thread.ofPlatform().start(() -> {
            try {
                outcome.set(setup.get(progress, "Downloading ffmpeg"));
            } catch (Throwable e) {
                outcome.set(e);
            }
        });
    }

    /** Waits until the thread is blocked (the item waiting for the one downloading). */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < deadline, "not blocked: " + thread.getState());
            Thread.sleep(10);
        }
    }

    @Test
    public void aWaiterCancelledWhileAnotherItemDownloadsStopsAloneAndTheDownloadGoesOn() throws Exception {
        release = new FakeRelease().release(build("n8.1-downloaded"));
        release.hang = new CountDownLatch(1);
        FfmpegSetup setup = FfmpegSetup.unshared(settings(null, true), context(true));
        CountDownLatch downloading = new CountDownLatch(1);
        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        Thread downloader = item(setup, status -> {
            if (status.actionPercent() >= 0) {
                downloading.countDown();
            }
        }, first);
        assertTrue(downloading.await(10, TimeUnit.SECONDS));
        Thread waiter = item(setup, ItemProgress.NONE, second);
        awaitBlocked(waiter);

        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(10));
        assertInstanceOf(InterruptedException.class, second.get(), "the waiter is cancelled");
        assertTrue(downloader.isAlive(), "the download goes on");

        release.hang.countDown();
        downloader.join(TimeUnit.SECONDS.toMillis(20));
        assertEquals("n8.1-downloaded", assertInstanceOf(Ffmpeg.class, first.get()).version().text());
        assertEquals("n8.1-downloaded", get(setup).version().text(), "no failure kept");
        assertEquals(1, release.requests(FakeRelease.ARCHIVE));
    }

    @Test
    public void aCancelledDownloadIsNotKeptAndTheNextItemDownloadsAgain() throws Exception {
        release = new FakeRelease().release(build("n8.1-downloaded"));
        release.hang = new CountDownLatch(1);
        FfmpegSetup setup = FfmpegSetup.unshared(settings(null, true), context(true));
        CountDownLatch downloading = new CountDownLatch(1);
        AtomicReference<Object> first = new AtomicReference<>();
        Thread downloader = item(setup, status -> {
            if (status.actionPercent() >= 0) {
                downloading.countDown();
            }
        }, first);
        assertTrue(downloading.await(10, TimeUnit.SECONDS));

        downloader.interrupt();
        downloader.join(TimeUnit.SECONDS.toMillis(20));
        assertInstanceOf(InterruptedException.class, first.get());
        assertFalse(Files.exists(installRoot().resolve("8.1")));

        CountDownLatch hang = release.hang;
        release.hang = null;
        hang.countDown();
        assertEquals("n8.1-downloaded", get(setup).version().text(), "the cancel was not kept as a failure");
        assertEquals(2, release.requests(FakeRelease.ARCHIVE));
    }

    @Test
    public void aFailedDownloadFailsEveryItemOfTheRunWithoutTryingAgain() throws Exception {
        release = new FakeRelease().release(build("n8.1"));
        release.file(FakeRelease.ARCHIVE, build("tampered"));
        FfmpegSetup setup = FfmpegSetup.unshared(settings(null, true), context(true));

        assertEquals("ffmpeg.download.error.digest", failure(setup));
        assertEquals("ffmpeg.download.error.digest", failure(setup));

        assertEquals(1, release.requests(FakeRelease.ARCHIVE));
        assertFalse(Files.exists(installRoot().resolve("8.1")));
    }

    @Test
    public void theWarningsBeforeTheRunTellWhatWillHappen() throws Exception {
        assertEquals(List.of("ffmpeg.not-found.download"), keys(settings(null, false), context(true)));
        assertEquals(List.of("ffmpeg.will-download"), keys(settings(null, true), context(true)));
        assertEquals(List.of("ffmpeg.not-found.install"), keys(settings(null, true), context(false)));
        assertEquals(List.of("ffmpeg.not-found.install"), keys(settings(null, false), context(false)));
        assertEquals(List.of("ffmpeg.path-not-found"), keys(settings(tempDir.resolve("no.exe").toString(), true),
                context(true)));
        assertEquals(List.of(), launchesAfter(), "nothing is run for the warnings");

        fake(pathFolder().resolve("ffmpeg.exe"), "n7.1-path");
        assertEquals(List.of(), keys(settings(null, false), context(true)));
    }

    private List<String> launchesAfter() {
        return launches.get() == 0 ? List.of() : List.of("launched " + launches.get());
    }

    @Test
    public void aWorkOfThePhaseIsDoneOnceForEveryCallerAndASharingStep() throws Exception {
        FfmpegSettings settings = FfmpegSettings.of(null, false);
        FfmpegSetup.Context context = context(false);
        FfmpegSetup first = FfmpegSetup.acquire(settings, context);
        FfmpegSetup second = FfmpegSetup.acquire(settings, context);
        AtomicInteger done = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                FfmpegSetup setup = i % 2 == 0 ? first : second;
                results.add(pool.submit(() -> setup.once("probe", () -> {
                    done.incrementAndGet();
                    started.countDown();
                    finish.await();
                    return "qsv";
                })));
            }
            assertTrue(started.await(10, TimeUnit.SECONDS));
            finish.countDown();
            for (Future<String> result : results) {
                assertEquals("qsv", result.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, done.get());
            assertEquals("other", first.once("other", () -> "other"), "another key: its own work");
        } finally {
            pool.shutdownNow();
            first.release();
            second.release();
        }
        FfmpegSetup next = FfmpegSetup.acquire(settings, context);
        try {
            assertEquals("again", next.once("probe", () -> "again"), "the next phase does it again");
        } finally {
            next.release();
        }
    }

    @Test
    public void aFailedOrInterruptedWorkOfThePhaseIsNotKept() throws Exception {
        FfmpegSetup setup = FfmpegSetup.unshared(FfmpegSettings.of(null, false), context(false));
        assertThrows(IllegalStateException.class, () -> setup.once("probe", () -> {
            throw new IllegalStateException("failed");
        }));
        assertThrows(InterruptedException.class, () -> setup.once("probe", () -> {
            throw new InterruptedException("cancelled");
        }));
        assertEquals("cpu", setup.once("probe", () -> "cpu"));
        assertEquals("cpu", setup.once("probe", () -> "never"));
    }

    private static List<String> keys(FfmpegSettings settings, FfmpegSetup.Context context) {
        return FfmpegSetup.warnings(settings, context).stream().map(FfmpegText::key).toList();
    }
}
