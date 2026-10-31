package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.exec.ExecPlugin;
import com.copybot.plugin.exec.ffmpeg.Ffmpeg;
import com.copybot.plugin.exec.ffmpeg.FfmpegSettings;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.process.FakeProgram;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The part every ffmpeg action shares (spec exec-ffmpeg §4), through a minimal action. */
public class FfmpegActionTest {

    private static Locale defaultLocale;

    @TempDir
    Path tempDir;

    private final AtomicInteger launches = new AtomicInteger();

    @BeforeAll
    static void english() {
        defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    /** The configuration of the minimal action: the two common fields only. */
    public record MinimalConfig(@FilePath String ffmpegPath, Boolean downloadFfmpeg) implements FfmpegSettings {
    }

    /** An ffmpeg action doing nothing but finding ffmpeg. */
    static final class MinimalAction extends FfmpegAction<MinimalConfig> {

        MinimalAction(FfmpegSetup.Context context) {
            super(() -> context);
        }

        @Override
        protected Class<MinimalConfig> getConfigClass() {
            return MinimalConfig.class;
        }

        @Override
        public List<WorkItem> doProcess(WorkItem item) {
            return List.of(item);
        }

        Ffmpeg find() throws InterruptedException {
            return ffmpeg();
        }
    }

    private FfmpegSetup.Context context(boolean downloadSupported) {
        ProgramResolver.Environment environment = new ProgramResolver.Environment(
                tempDir.resolve("path").toString(), ".EXE", true);
        return new FfmpegSetup.Context(environment, tempDir.resolve("data"), downloadSupported, null,
                executable -> {
                    launches.incrementAndGet();
                    try {
                        return FakeProgram.command("ffmpeg", Files.readString(executable));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private MinimalAction action(FfmpegSetup.Context context, Boolean download) {
        MinimalAction action = new MinimalAction(context);
        ExecPlugin plugin = new ExecPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(ExecPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ENGLISH);
        plugin.setResourceBundle(bundle);
        action.setPlugin(plugin);
        JsonObject config = new JsonObject();
        if (download != null) {
            config.addProperty("downloadFfmpeg", download);
        }
        action.loadConfig(config);
        return action;
    }

    @Test
    public void theWarningsAreLocalised() {
        assertEquals(List.of("ffmpeg is not found: tick \u201cDownload ffmpeg if it is not found\u201d, give its"
                + " path, or install it in the PATH"), action(context(true), false).configWarnings());
        assertEquals(List.of("ffmpeg is not found: it will be downloaded when the first file needs it (about 200 MB,"
                + " the GPL build of BtbN/FFmpeg-Builds)"), action(context(true), true).configWarnings());
        assertTrue(action(context(false), true).configWarnings().getFirst()
                .contains("apt install ffmpeg"), "no download outside Windows x64");
    }

    @Test
    public void ffmpegNotFoundFailsTheItemWithTheLocalisedMessage() {
        MinimalAction action = action(context(true), null);
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, action::find);
            assertTrue(e.getMessage().startsWith("ffmpeg is not found: tick"), e.getMessage());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void theStepsOfARunShareTheVersionReadOnce() throws Exception {
        Files.createDirectories(tempDir.resolve("path"));
        Files.writeString(tempDir.resolve("path").resolve("ffmpeg.exe"), "n7.1-path");
        FfmpegSetup.Context context = context(false);
        MinimalAction video = action(context, null);
        MinimalAction thumbnail = action(context, false);

        video.beforeAll();
        thumbnail.beforeAll();
        try {
            assertEquals("n7.1-path", video.find().version().text());
            assertEquals("n7.1-path", thumbnail.find().version().text());
            assertEquals("n7.1-path", video.find().version().text());
            assertEquals(1, launches.get());
        } finally {
            video.afterAll();
            thumbnail.afterAll();
        }

        video.beforeAll();
        try {
            video.find();
            assertEquals(2, launches.get(), "read again in the next run");
        } finally {
            video.afterAll();
        }
    }

    @Test
    public void anActionUsedOutsideARunStillFindsFfmpeg() throws Exception {
        Files.createDirectories(tempDir.resolve("path"));
        Files.writeString(tempDir.resolve("path").resolve("ffmpeg.exe"), "n8.1-path");

        assertEquals(8, action(context(false), null).find().version().major());
    }
}
