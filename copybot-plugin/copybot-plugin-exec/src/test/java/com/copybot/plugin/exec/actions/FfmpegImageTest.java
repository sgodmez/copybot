package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.exec.ExecPlugin;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.process.FakeProgram;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonArray;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ffmpeg.image (spec exec-ffmpeg §6) with a fake ffmpeg ({@code FakeProgram ffmpeg-image}). */
public class FfmpegImageTest {

    private static final Instant CAPTURE = Instant.parse("2024-07-14T10:15:30Z");
    private static final Instant MODIFIED = Instant.parse("2025-01-02T03:04:05Z");

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

    /** The version the fake ffmpeg gives. */
    private void version(String version) throws IOException {
        Files.createDirectories(state());
        Files.writeString(state().resolve("version"), version);
    }

    private FfmpegSetup.Context context() throws IOException {
        if (context == null) {
            Path path = Files.createDirectories(tempDir.resolve("path"));
            Files.writeString(path.resolve("ffmpeg.exe"), "fake");
            Files.createDirectories(state());
            context = new FfmpegSetup.Context(new ProgramResolver.Environment(path.toString(), ".EXE", true),
                    tempDir.resolve("data"), false, null,
                    executable -> FakeProgram.command("ffmpeg-image", state().toString()));
        }
        return context;
    }

    private FfmpegImage action(JsonObject config) throws IOException {
        FfmpegSetup.Context context = context();
        FfmpegImage action = new FfmpegImage(work(), () -> context);
        ExecPlugin plugin = new ExecPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(ExecPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ENGLISH);
        plugin.setResourceBundle(bundle);
        action.setPlugin(plugin);
        action.loadConfig(config);
        return action;
    }

    private static JsonObject extensions(String... extensions) {
        JsonObject config = new JsonObject();
        JsonArray list = new JsonArray();
        for (String extension : extensions) {
            list.add(extension);
        }
        config.add("onlyExtensions", list);
        return config;
    }

    private WorkItem file(String name, String content) throws IOException {
        Path source = Files.writeString(Files.createDirectories(tempDir.resolve("in")).resolve(name), content);
        Files.setLastModifiedTime(source, FileTime.from(Instant.parse("2020-05-06T07:08:09Z")));
        WorkItem item = new WorkItem(source);
        item.getMetadatas().display().put("name", name);
        item.getMetadatas().setSize(content.length());
        return item;
    }

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

    private List<List<String>> conversions() throws IOException {
        return calls().stream().filter(call -> call.contains("-i")).toList();
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
    public void aHeicPhotoBecomesAJpegDatedByItsCapture() throws Exception {
        FfmpegImage action = action(new JsonObject());
        action.beforeAll();
        WorkItem item = file("IMG_0001.HEIC", "pixels");
        item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, CAPTURE);
        item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, MODIFIED);

        assertEquals(Set.of("cpu"), action.requiredResources(item));
        List<WorkItem> produced = action.doProcess(item);

        assertEquals(1, produced.size());
        WorkItem result = produced.getFirst();
        assertEquals("jpg:pixels", content(result));
        assertEquals("IMG_0001.jpg", result.getMetadatas().display().get("name"));
        assertEquals(10L, result.getMetadatas().getSize());
        assertEquals(item.getLocalLocation(), result.getLocalLocation(), "the source stays the original");
        assertEquals(CAPTURE, Files.getLastModifiedTime(result.getContentLocation()).toInstant());
        assertEquals(CAPTURE, result.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow(),
                "the writer keeps this date on the target");
        assertEquals(MODIFIED, item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow(),
                "the source item is unchanged");
        List<String> conversion = conversions().getFirst();
        assertEquals(item.getLocalLocation().toString(), conversion.get(conversion.indexOf("-i") + 1));
        assertEquals("5", conversion.get(conversion.indexOf("-q:v") + 1), "quality 90");
        assertFalse(conversion.contains("-vf"));
        assertEquals(result.getContentLocation().toString(), conversion.getLast());

        action.afterItem(item);
        assertFalse(Files.exists(result.getContentLocation()));
        action.afterAll();
        assertEquals(0, workFiles());
    }

    @Test
    public void withoutACaptureDateTheJpegHasTheDateOfTheSource() throws Exception {
        FfmpegImage action = action(new JsonObject());
        action.beforeAll();
        try {
            WorkItem listed = file("a.heif", "pixels");
            listed.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, MODIFIED);
            WorkItem result = action.doProcess(listed).getFirst();
            assertEquals(MODIFIED, Files.getLastModifiedTime(result.getContentLocation()).toInstant());
            assertEquals(MODIFIED, result.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());

            WorkItem bare = file("b.heic", "pixels"); // no metadata: the date of its file
            WorkItem other = action.doProcess(bare).getFirst();
            assertEquals(Instant.parse("2020-05-06T07:08:09Z"),
                    Files.getLastModifiedTime(other.getContentLocation()).toInstant());
            assertTrue(other.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).isEmpty());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void theQualityAndTheSizeReachFfmpeg() throws Exception {
        JsonObject config = new JsonObject();
        config.addProperty("quality", 100);
        config.addProperty("maxSize", 1600);
        FfmpegImage action = action(config);
        action.beforeAll();
        try {
            action.doProcess(file("photo.heic", "pixels"));
            List<String> conversion = conversions().getFirst();
            assertEquals("2", conversion.get(conversion.indexOf("-q:v") + 1));
            assertEquals("scale=w='min(iw,1600)':h='min(ih,1600)':force_original_aspect_ratio=decrease,setsar=1",
                    conversion.get(conversion.indexOf("-vf") + 1));
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void onlyTheChosenImageExtensionsAreConverted() throws Exception {
        FfmpegImage heic = action(new JsonObject());
        WorkItem png = file("shot.png", "pixels");
        WorkItem video = file("clip.mov", "frames");
        assertSame(png, heic.doProcess(png).getFirst(), "png is not among heic, heif");
        assertSame(video, heic.doProcess(video).getFirst());
        assertEquals(List.of(), calls(), "ffmpeg is not even looked for");

        FfmpegImage chosen = action(extensions(".PNG", "txt"));
        chosen.beforeAll();
        try {
            assertEquals("shot.jpg", chosen.doProcess(png).getFirst().getMetadatas().display().get("name"));
            WorkItem text = file("notes.txt", "words");
            assertSame(text, chosen.doProcess(text).getFirst(), "not an image ffmpeg reads");
            WorkItem photo = file("photo.heic", "pixels");
            assertSame(photo, chosen.doProcess(photo).getFirst(), "not chosen");
        } finally {
            chosen.afterAll();
        }

        FfmpegImage every = action(extensions());
        every.beforeAll();
        try {
            assertEquals("photo.jpg", every.doProcess(file("photo.webp", "pixels")).getFirst()
                    .getMetadatas().display().get("name"), "an empty list: every image");
        } finally {
            every.afterAll();
        }
    }

    @Test
    public void aHeicPhotoNeedsFfmpeg71() throws Exception {
        version("6.0-essentials_build-www.gyan.dev");
        FfmpegImage action = action(extensions("heic", "png"));
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("photo.heic", "pixels")));
            assertEquals("ffmpeg.image: photo.heic is a HEIC photo, which needs ffmpeg 7.1 or later; the ffmpeg"
                    + " found is version 6.0-essentials_build-www.gyan.dev", e.getMessage());
            assertEquals(List.of(), conversions(), "not run");
            assertEquals(0, workFiles());
            assertEquals("shot.jpg", action.doProcess(file("shot.png", "pixels")).getFirst()
                    .getMetadatas().display().get("name"), "other images are read by older versions");
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aDevelopmentBuildReadsHeic() throws Exception {
        version("N-117000-g1234567");
        FfmpegImage action = action(new JsonObject());
        action.beforeAll();
        try {
            assertEquals("photo.jpg", action.doProcess(file("photo.heic", "pixels")).getFirst()
                    .getMetadatas().display().get("name"));
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aFailureGivesTheEndOfTheErrorOutput() throws Exception {
        FfmpegImage action = action(new JsonObject());
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("broken.heic", "FAIL")));
            assertTrue(e.getMessage().startsWith("ffmpeg.image: ffmpeg failed on broken.heic (code 1): "),
                    e.getMessage());
            assertTrue(e.getMessage().contains("Invalid data found when processing input"), e.getMessage());
            IllegalStateException empty = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("empty.heic", "EMPTY")));
            assertEquals("ffmpeg.image: ffmpeg wrote no file, or an empty one, for empty.heic", empty.getMessage());
            assertEquals(0, workFiles());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void beyondTheTimeLimitFfmpegIsStopped() throws Exception {
        JsonObject config = new JsonObject();
        config.addProperty("timeoutSeconds", 1);
        FfmpegImage action = action(config);
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("long.heic", "SLEEP")));
            assertEquals("ffmpeg.image: ffmpeg was stopped on long.heic: time limit of 1 s exceeded",
                    e.getMessage());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aCancelStopsFfmpegAndIsNoError() throws Exception {
        FfmpegImage action = action(new JsonObject());
        action.beforeAll();
        WorkItem item = file("long.heic", "SLEEP");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                action.doProcess(item);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (conversions().isEmpty() && System.nanoTime() < deadline) {
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
    public void theDryRunNamesTheJpegsAndRunsNothing() throws Exception {
        FfmpegImage action = action(new JsonObject());
        WorkItem photo = file("photo.HEIC", "pixels");
        photo.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, CAPTURE);
        WorkItem png = file("shot.png", "pixels");

        WorkItem projected = action.dryRun(photo).orElseThrow().getFirst();
        assertEquals("photo.jpg", projected.getMetadatas().display().get("name"));
        assertEquals(CAPTURE, projected.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED).orElseThrow());
        assertEquals("shot.png", action.dryRun(png).orElseThrow().getFirst().getMetadatas().display().get("name"));
        assertEquals(List.of(), calls());
    }
}
