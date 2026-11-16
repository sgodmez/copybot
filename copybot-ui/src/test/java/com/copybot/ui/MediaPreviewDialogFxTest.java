package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The preview window on real files (what it shows, not how it looks); skipped without display. */
public class MediaPreviewDialogFxTest {

    private static final AtomicBoolean TOOLKIT = new AtomicBoolean();
    /** A PNG of 1 x 1 pixel. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    /** The engine neither prepares nor copies. */
    private static final BooleanSupplier IDLE = () -> false;

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
            TOOLKIT.set(started.await(10, TimeUnit.SECONDS));
        } catch (IllegalStateException alreadyStarted) {
            TOOLKIT.set(true);
        } catch (RuntimeException | UnsatisfiedLinkError noDisplay) {
            TOOLKIT.set(false);
        }
        if (TOOLKIT.get()) {
            Platform.setImplicitExit(false);
        }
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /** What the window ends up showing once the file is read (in the background) and decoded. */
    private static String settled(MediaPreviewDialog dialog) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String shown = onFx(dialog::shown);
        while ("preview-loading".equals(shown) && System.nanoTime() < end) {
            Thread.sleep(20);
            shown = onFx(dialog::shown);
        }
        return shown;
    }

    @Test
    public void showsEachFileOrWhyNotAndGoesThroughThem() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        Path png = Files.write(dir.resolve("a.png"), PNG);
        Path raw = Files.write(dir.resolve("b.CR2"), new byte[]{1, 2, 3});
        Path missing = dir.resolve("c.jpg");
        Path corrupt = Files.write(dir.resolve("d.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        Path video = Files.write(dir.resolve("e.mp4"), new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        // without a codec (GStreamer missing on a Linux runner) the damaged video neither plays nor fails: the load
        // timeout gives up, well before settled() stops waiting
        javafx.util.Duration timeout = MediaPreviewDialog.loadTimeout;
        MediaPreviewDialog.loadTimeout = javafx.util.Duration.seconds(3);
        MediaPreviewDialog dialog = onFx(() -> MediaPreviewDialog.show(null, IDLE, List.of(png, raw, missing, corrupt, video), 0));
        try {
            assertEquals("preview-image", settled(dialog));
            onFx(() -> {
                dialog.previous(); // already the first one
                return null;
            });
            assertEquals(0, onFx(dialog::index));
            List<String> following = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                onFx(() -> {
                    dialog.next();
                    return null;
                });
                following.add(settled(dialog));
            }
            assertEquals(List.of("preview-other", "preview-missing", "preview-failed", "preview-failed"), following);
            onFx(() -> {
                dialog.next(); // already the last one
                return null;
            });
            assertEquals(4, onFx(dialog::index));
        } finally {
            MediaPreviewDialog.loadTimeout = timeout;
            onFx(() -> {
                dialog.close();
                return null;
            });
        }
        assertTrue(released(video), "the video stays open after the preview is closed");
    }

    @Test
    public void aVideoLeftBeforeItIsReadIsReleased() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        Path video = Files.write(dir.resolve("a.mp4"), new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        Path raw = Files.write(dir.resolve("b.nef"), new byte[]{1});
        // the next file at once: the video is still read in the background, its result comes too late
        MediaPreviewDialog dialog = onFx(() -> {
            MediaPreviewDialog shown = MediaPreviewDialog.show(null, IDLE, List.of(video, raw), 0);
            shown.next();
            return shown;
        });
        try {
            assertEquals("preview-other", settled(dialog));
            assertTrue(released(video), "the video read too late stays open");
        } finally {
            onFx(() -> {
                dialog.close();
                return null;
            });
        }
    }

    @Test
    public void playsASoundThenReleasesItsFile() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        // the CI runs on Linux without a sound device: the player fails there
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "a sound device");
        Path sound = Files.write(dir.resolve("a.wav"), wav());
        MediaPreviewDialog dialog = onFx(() -> MediaPreviewDialog.show(null, IDLE, List.of(sound), 0));
        try {
            assertEquals("preview-audio", settled(dialog));
        } finally {
            onFx(() -> {
                dialog.close();
                return null;
            });
        }
        assertTrue(released(sound), "the sound stays open after the preview is closed");
    }

    @Test
    public void noSoundNorVideoIsOpenedWhileTheEngineIsBusy() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        Path sound = Files.write(dir.resolve("a.wav"), wav());
        MediaPreviewDialog dialog = onFx(() -> MediaPreviewDialog.show(null, () -> true, List.of(sound), 0));
        try {
            assertEquals("preview-busy", settled(dialog));
            // never opened: a copy in "move" mode can delete it
            assertTrue(released(sound), "the sound is open while the engine is busy");
        } finally {
            onFx(() -> {
                dialog.close();
                return null;
            });
        }
    }

    @Test
    public void theEngineStartingStopsTheSoundAndReleasesItsFile() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "a sound device");
        Path sound = Files.write(dir.resolve("a.wav"), wav());
        AtomicBoolean busy = new AtomicBoolean();
        MediaPreviewDialog dialog = onFx(() -> MediaPreviewDialog.show(null, busy::get, List.of(sound), 0));
        try {
            assertEquals("preview-audio", settled(dialog));
            busy.set(true);
            onFx(() -> {
                MediaPreviewDialog.engineBusy();
                return null;
            });
            assertEquals("preview-busy", settled(dialog));
            assertTrue(released(sound), "the sound stays open once the engine is busy");
        } finally {
            onFx(() -> {
                dialog.close();
                return null;
            });
        }
    }

    @Test
    public void anotherPreviewReusesTheOpenWindow() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        Path png = Files.write(dir.resolve("a.png"), PNG);
        Path raw = Files.write(dir.resolve("b.nef"), new byte[]{1});
        MediaPreviewDialog first = onFx(() -> MediaPreviewDialog.show(null, IDLE, List.of(png, raw), 0));
        try {
            assertEquals("preview-image", settled(first));
            MediaPreviewDialog second = onFx(() -> MediaPreviewDialog.show(null, IDLE, List.of(raw), 0));
            assertTrue(first == second, "a second window is opened");
            assertEquals("preview-other", settled(first));
            assertEquals(0, onFx(first::index));
        } finally {
            onFx(() -> {
                first.close();
                return null;
            });
        }
    }

    /** Half a second of silence: 8 kHz, 8 bits, mono. */
    private static byte[] wav() {
        int samples = 4000;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + samples).put("WAVE".getBytes());
        wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(8000)
                .putShort((short) 1).putShort((short) 8);
        wav.put("data".getBytes()).putInt(samples);
        while (wav.hasRemaining()) {
            wav.put((byte) 128);
        }
        return wav.array();
    }

    /**
     * The file can be moved (Windows refuses while it is open) within a few seconds: JavaFX releases a media
     * asynchronously, shortly after its player is disposed.
     */
    private static boolean released(Path file) throws InterruptedException {
        Path moved = file.resolveSibling(file.getFileName() + ".moved");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            try {
                Files.move(file, moved);
                Files.move(moved, file);
                return true;
            } catch (IOException open) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    @Test
    public void startsFromTheClickedFile() throws Exception {
        assumeTrue(TOOLKIT.get(), "no JavaFX toolkit (no display)");
        Path png = Files.write(dir.resolve("a.png"), PNG);
        Path raw = Files.write(dir.resolve("b.nef"), new byte[]{1});
        MediaPreviewDialog dialog = onFx(() -> MediaPreviewDialog.show(null, IDLE, List.of(png, raw), 1));
        try {
            assertEquals("preview-other", settled(dialog));
            assertEquals(1, onFx(dialog::index));
        } finally {
            onFx(() -> {
                dialog.close();
                return null;
            });
        }
    }
}
