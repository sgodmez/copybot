package com.copybot.plugin.ffmpeg.actions;

import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.ffmpeg.FfmpegPlugin;
import com.copybot.plugin.ffmpeg.ffmpeg.FfmpegSetup;
import com.copybot.plugin.ffmpeg.FakeProgram;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ffmpeg.video (spec exec-ffmpeg §5) with a fake ffmpeg ({@code FakeProgram ffmpeg-video}). */
public class FfmpegVideoTest {

    private static Locale defaultLocale;

    @TempDir
    Path tempDir;

    private FfmpegSetup.Context context;

    @BeforeAll
    static void english() {
        defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    private Path state() {
        return tempDir.resolve("state");
    }

    private Path work() {
        return tempDir.resolve("work");
    }

    /** The encoders the fake ffmpeg can use. */
    private void encoders(String... names) throws IOException {
        Files.createDirectories(state());
        Files.write(state().resolve("encoders"), List.of(names));
    }

    /** The same for every action of a test: the steps of a run share their ffmpeg by settings and context. */
    private FfmpegSetup.Context context() throws IOException {
        if (context == null) {
            Path path = Files.createDirectories(tempDir.resolve("path"));
            Files.writeString(path.resolve("ffmpeg.exe"), "fake");
            Files.createDirectories(state());
            context = new FfmpegSetup.Context(new ProgramResolver.Environment(path.toString(), ".EXE", true),
                    tempDir.resolve("data"), false, null,
                    executable -> FakeProgram.command("ffmpeg-video", state().toString()));
        }
        return context;
    }

    private FfmpegVideo action(JsonObject config) throws IOException {
        FfmpegSetup.Context context = context();
        FfmpegVideo action = new FfmpegVideo(work(), () -> context);
        FfmpegPlugin plugin = new FfmpegPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(FfmpegPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ENGLISH);
        plugin.setResourceBundle(bundle);
        action.setPlugin(plugin);
        action.loadConfig(config);
        return action;
    }

    private static JsonObject config(String... pairs) {
        JsonObject config = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            config.addProperty(pairs[i], pairs[i + 1]);
        }
        return config;
    }

    private WorkItem file(String name, String content) throws IOException {
        Path source = Files.writeString(Files.createDirectories(tempDir.resolve("in")).resolve(name), content);
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", name);
        item.getMetadatas().setSize(content.length());
        return item;
    }

    /** The arguments of each call of the fake ffmpeg. */
    private List<List<String>> calls() throws IOException {
        Path calls = state().resolve("calls");
        if (!Files.isDirectory(calls)) {
            return List.of();
        }
        List<List<String>> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(calls)) {
            for (Path file : files.toList()) {
                all.add(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        return all;
    }

    /** The encoders probed, in their order. */
    private List<String> probes() throws IOException {
        // the call files are named at random: the order of the probes is the one of their creation
        Path calls = state().resolve("calls");
        if (!Files.isDirectory(calls)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(calls)) {
            files = new ArrayList<>(listed.toList());
        }
        files.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        List<String> probed = new ArrayList<>();
        for (Path file : files) {
            List<String> arguments = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (arguments.contains("lavfi")) {
                probed.add(arguments.get(arguments.indexOf("-c:v") + 1));
            }
        }
        return probed;
    }

    /** The calls encoding a file. */
    private List<List<String>> encodings() throws IOException {
        return calls().stream().filter(call -> call.contains("-i") && !call.contains("lavfi")).toList();
    }

    private static String content(WorkItem item) throws IOException {
        try (InputStream in = item.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long workFiles() throws IOException {
        if (!Files.exists(work())) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(work())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    public void aVideoBecomesAnMp4AndItsProgressIsReported() throws Exception {
        FfmpegVideo action = action(config("encoder", "cpu"));
        action.beforeAll();
        WorkItem item = file("clip.mov", "frames");
        List<Integer> percents = Collections.synchronizedList(new ArrayList<>());

        assertEquals(Set.of("cpu"), action.requiredResources(item));
        List<WorkItem> produced = ItemProgress.callWith(status -> percents.add(status.actionPercent()),
                () -> action.doProcess(item));

        assertEquals(1, produced.size());
        WorkItem result = produced.getFirst();
        assertEquals("mp4:frames", content(result));
        assertEquals("clip.mp4", result.getMetadatas().display().get("name"));
        assertEquals(10L, result.getMetadatas().getSize());
        assertEquals(item.getLocalLocation(), result.getLocalLocation(), "the source stays the original");
        assertEquals(List.of(25, 50, 99, 100), percents);
        assertTrue(probes().isEmpty(), "cpu: nothing probed");
        List<String> encoding = encodings().getFirst();
        assertEquals(item.getLocalLocation().toString(), encoding.get(encoding.indexOf("-i") + 1));
        assertEquals("libx265", encoding.get(encoding.indexOf("-c:v") + 1));
        assertEquals(result.getContentLocation().toString(), encoding.getLast());

        action.afterItem(item);
        assertFalse(Files.exists(result.getContentLocation()));
        action.afterAll();
        assertEquals(0, workFiles());
    }

    @Test
    public void theOptionsOfTheConfigurationReachFfmpeg() throws Exception {
        JsonObject config = config("encoder", "cpu", "codec", "h264", "quality", "small", "audio", "copy");
        config.addProperty("maxHeight", 720);
        FfmpegVideo action = action(config);
        action.beforeAll();
        try {
            action.doProcess(file("clip.MTS", "frames"));
            List<String> encoding = encodings().getFirst();
            assertEquals("libx264", encoding.get(encoding.indexOf("-c:v") + 1));
            assertEquals("26", encoding.get(encoding.indexOf("-crf") + 1));
            assertEquals("copy", encoding.get(encoding.indexOf("-c:a") + 1));
            assertEquals("scale=-2:'min(720,trunc(ih/2)*2)'", encoding.get(encoding.indexOf("-vf") + 1));
            assertFalse(encoding.contains("-tag:v"));
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void autoTakesTheFirstHardwareEncoderThatWorksOncePerRunForEveryStep() throws Exception {
        encoders("hevc_qsv", "hevc_amf");
        FfmpegVideo video = action(new JsonObject());
        FfmpegVideo other = action(config("quality", "high"));
        video.beforeAll();
        other.beforeAll();
        try {
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 4; i++) {
                FfmpegVideo step = i % 2 == 0 ? video : other;
                WorkItem item = file("clip" + i + ".mp4", "frames " + i);
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        assertEquals(Set.of("gpu"), step.requiredResources(item));
                        step.doProcess(item);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(60));
            }
            assertEquals(List.of(), failures);
            assertEquals(List.of("hevc_nvenc", "hevc_qsv"), probes(), "nvenc fails, qsv works: amf never tried");
            assertEquals(4, encodings().size());
            encodings().forEach(call -> assertEquals("hevc_qsv", call.get(call.indexOf("-c:v") + 1)));
        } finally {
            video.afterAll();
            other.afterAll();
        }

        video.beforeAll();
        try {
            video.requiredResources(file("next.mp4", "frames"));
            assertEquals(4, probes().size(), "probed again in the next run");
        } finally {
            video.afterAll();
        }
    }

    @Test
    public void autoWithoutAWorkingHardwareEncoderIsTheProcessor() throws Exception {
        FfmpegVideo action = action(config("codec", "h264"));
        action.beforeAll();
        try {
            WorkItem item = file("clip.avi", "frames");
            assertEquals(Set.of("cpu"), action.requiredResources(item));
            action.doProcess(item);
            assertEquals(List.of("h264_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox", "h264_vaapi"), probes());
            List<String> encoding = encodings().getFirst();
            assertEquals("libx264", encoding.get(encoding.indexOf("-c:v") + 1));
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void anExplicitEncoderFailingItsProbeFallsBackToTheProcessorWithAWarning() throws Exception {
        Logger logger = Logger.getLogger(FfmpegVideo.class.getName());
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(java.text.MessageFormat.format(record.getMessage(), record.getParameters()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setUseParentHandlers(false); // the expected warning is not printed
        FfmpegVideo action = action(config("encoder", "nvenc"));
        action.beforeAll();
        try {
            WorkItem first = file("a.mp4", "frames");
            WorkItem second = file("b.mp4", "frames");
            assertEquals(Set.of("cpu"), action.requiredResources(first));
            action.doProcess(first);
            action.doProcess(second);
            assertEquals(List.of("hevc_nvenc"), probes(), "probed once");
            encodings().forEach(call -> assertEquals("libx265", call.get(call.indexOf("-c:v") + 1)));
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("hevc_nvenc"), warnings.getFirst());
        } finally {
            action.afterAll();
            logger.removeHandler(handler);
            logger.setUseParentHandlers(true);
        }
    }

    @Test
    public void anExplicitEncoderThatWorksTakesTheGpu() throws Exception {
        encoders("h264_vaapi");
        FfmpegVideo action = action(config("encoder", "vaapi", "codec", "h264"));
        action.beforeAll();
        try {
            WorkItem item = file("clip.mkv", "frames");
            assertEquals(Set.of("gpu"), action.requiredResources(item));
            action.doProcess(item);
            List<String> encoding = encodings().getFirst();
            assertEquals("h264_vaapi", encoding.get(encoding.indexOf("-c:v") + 1));
            assertEquals("/dev/dri/renderD128", encoding.get(encoding.indexOf("-vaapi_device") + 1));
            assertEquals(List.of("h264_vaapi"), probes());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void anItemThatIsNoVideoPassesUnchanged() throws Exception {
        FfmpegVideo action = action(new JsonObject());
        action.beforeAll();
        try {
            WorkItem photo = file("photo.jpg", "jpeg");
            assertEquals(Set.of("cpu"), action.requiredResources(photo));
            assertSame(photo, action.doProcess(photo).getFirst());
            assertEquals(List.of(), calls(), "ffmpeg is not even looked for");

            WorkItem song = file("song.mp4", "NO VIDEO");
            List<WorkItem> produced = action.doProcess(song);
            assertEquals(List.of(song), produced, "no video stream: unchanged");
            assertEquals("song.mp4", song.getMetadatas().display().get("name"));
            assertEquals(0, workFiles(), "its working folder is gone");
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aFailureGivesTheEndOfTheErrorOutputWithoutTheProgress() throws Exception {
        FfmpegVideo action = action(config("encoder", "cpu"));
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("broken.mov", "FAIL")));
            assertTrue(e.getMessage().startsWith("ffmpeg.video: ffmpeg failed on broken.mov (code 1): "),
                    e.getMessage());
            assertTrue(e.getMessage().contains("Invalid data found when processing input"), e.getMessage());
            assertFalse(e.getMessage().contains("out_time_us"), e.getMessage());
            assertEquals(0, workFiles());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void beyondTheTimeLimitFfmpegIsStopped() throws Exception {
        JsonObject config = config("encoder", "cpu");
        config.addProperty("timeoutSeconds", 1);
        FfmpegVideo action = action(config);
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("long.mov", "SLEEP")));
            assertEquals("ffmpeg.video: ffmpeg was stopped on long.mov: time limit of 1 s exceeded",
                    e.getMessage());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aCancelStopsFfmpegAndIsNoError() throws Exception {
        FfmpegVideo action = action(config("encoder", "cpu"));
        action.beforeAll();
        WorkItem item = file("long.mov", "SLEEP");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                action.doProcess(item);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (encodings().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(worker.isAlive());
        assertInstanceOf(CancellationException.class, thrown.get());
        action.afterAll();
        assertEquals(0, workFiles());
    }

    @Test
    public void aCancelWhileTheResourcesAreToldKeepsTheInterruptAndTakesTheProcessor() throws Exception {
        encoders("hevc_nvenc");
        FfmpegVideo action = action(new JsonObject());
        action.beforeAll();
        try {
            WorkItem item = file("clip.mp4", "frames");
            Thread.currentThread().interrupt(); // a cancel: the wait for the ffmpeg of the run is interrupted
            Set<String> resources = action.requiredResources(item);
            assertTrue(Thread.interrupted(), "the interrupt status is set again, for the engine's wait");
            assertEquals(Set.of("cpu"), resources);
            assertEquals(List.of(), probes(), "nothing probed, nothing kept");

            assertEquals(Set.of("gpu"), action.requiredResources(item), "the next call chooses");
        } finally {
            Thread.interrupted();
            action.afterAll();
        }
    }

    @Test
    public void theDryRunNamesTheVideosInMp4AndRunsNothing() throws Exception {
        FfmpegVideo action = action(new JsonObject());
        WorkItem video = file("clip.MOV", "frames");
        WorkItem photo = file("photo.heic", "heic");

        assertEquals("clip.mp4",
                action.dryRun(video).orElseThrow().getFirst().getMetadatas().display().get("name"));
        assertEquals("photo.heic",
                action.dryRun(photo).orElseThrow().getFirst().getMetadatas().display().get("name"));
        assertEquals(List.of(), calls());
    }

    @Test
    public void anUnknownValueIsWarnedAndFailsTheVideos() throws Exception {
        FfmpegVideo action = action(config("codec", "av1", "encoder", "cpu"));

        assertTrue(action.configWarnings().contains("ffmpeg.video: unknown codec “av1”"),
                action.configWarnings().toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action.doProcess(file("clip.mp4", "frames")));
        assertEquals("ffmpeg.video: unknown codec “av1”", e.getMessage());
        assertEquals(Set.of("cpu"), action.requiredResources(file("clip2.mp4", "frames")));
    }
}
