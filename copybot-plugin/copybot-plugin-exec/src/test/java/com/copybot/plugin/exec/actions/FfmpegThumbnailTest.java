package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.exec.ExecPlugin;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ffmpeg.thumbnail (spec exec-ffmpeg §7) with a fake ffmpeg ({@code FakeProgram ffmpeg-image}). */
public class FfmpegThumbnailTest {

    private static final String SCALE_320 =
            "scale=w='min(iw,320)':h='min(ih,320)':force_original_aspect_ratio=decrease,setsar=1";

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

    private FfmpegThumbnail action(JsonObject config) throws IOException {
        FfmpegSetup.Context context = context();
        FfmpegThumbnail action = new FfmpegThumbnail(work(), () -> context);
        ExecPlugin plugin = new ExecPlugin();
        CombinedResourceBundle bundle = new CombinedResourceBundle(ExecPlugin.class.getModule(), null);
        plugin.getI18nBundleNames().forEach(bundle::addBundle);
        bundle.load(Locale.ENGLISH);
        plugin.setResourceBundle(bundle);
        action.setPlugin(plugin);
        action.loadConfig(config);
        return action;
    }

    private WorkItem file(String name, String content) throws IOException {
        Path source = Files.writeString(Files.createDirectories(tempDir.resolve("in")).resolve(name), content);
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

    /** The calls writing a thumbnail (not the version, not an inspection). */
    private List<List<String>> thumbnails() throws IOException {
        return calls().stream().filter(call -> call.contains("-frames:v")).toList();
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
    public void anImageIsReducedIntoAThumbnail() throws Exception {
        FfmpegThumbnail action = action(new JsonObject());
        action.beforeAll();
        WorkItem item = file("photo.jpg", "pixels");

        assertEquals(Set.of("cpu"), action.requiredResources(item));
        List<WorkItem> produced = action.doProcess(item);

        assertEquals(1, produced.size());
        WorkItem result = produced.getFirst();
        assertEquals("jpg:pixels", content(result));
        assertEquals("photo-thumb.jpg", result.getMetadatas().display().get("name"));
        assertEquals(10L, result.getMetadatas().getSize());
        assertEquals(item.getLocalLocation(), result.getLocalLocation());
        assertEquals(1, thumbnails().size());
        List<String> call = thumbnails().getFirst();
        assertEquals(SCALE_320, call.get(call.indexOf("-vf") + 1));
        assertEquals("6", call.get(call.indexOf("-q:v") + 1), "JPEG quality 85");
        assertFalse(call.contains("-ss"));
        assertFalse(call.contains("-map"), "the largest picture of the image");
        assertEquals(result.getContentLocation().toString(), call.getLast());

        action.afterItem(item);
        action.afterAll();
        assertEquals(0, workFiles());
    }

    @Test
    public void aVideoGivesItsFrameAtATenthOfItsDurationAtMostTenSeconds() throws Exception {
        JsonObject config = new JsonObject();
        config.addProperty("size", 160);
        FfmpegThumbnail action = action(config);
        action.beforeAll();
        try {
            WorkItem clip = file("clip.MOV", "VIDEO 00:00:30.00");
            WorkItem result = action.doProcess(clip).getFirst();
            assertEquals("clip-video-thumb.jpg", result.getMetadatas().display().get("name"));
            List<String> call = thumbnails().getFirst();
            assertEquals("3.000", call.get(call.indexOf("-ss") + 1));
            assertTrue(call.indexOf("-ss") < call.indexOf("-i"), "a fast seek in the input");
            assertEquals("0:V:0", call.get(call.indexOf("-map") + 1));
            assertEquals("1", call.get(call.indexOf("-frames:v") + 1));
            assertEquals("scale=w='min(iw,160)':h='min(ih,160)':force_original_aspect_ratio=decrease,setsar=1",
                    call.get(call.indexOf("-vf") + 1));

            action.doProcess(file("film.mkv", "VIDEO 01:30:00.00"));
            assertTrue(thumbnails().stream().anyMatch(c -> c.contains("10.000")), "at most 10 s");
            action.doProcess(file("stream.ts", "VIDEO N/A"));
            assertTrue(thumbnails().stream().anyMatch(c -> c.getLast().endsWith("stream-video-thumb.jpg") &&!c.contains("-ss")),
                    "unknown duration: the first frame");
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aSeekThatGivesNothingIsRetriedAtTheFirstFrame() throws Exception {
        FfmpegThumbnail action = action(new JsonObject());
        action.beforeAll();
        try {
            WorkItem result = action.doProcess(file("clip.mp4", "VIDEO 00:00:30.00 SEEK-EMPTY")).getFirst();
            assertEquals("clip-video-thumb.jpg", result.getMetadatas().display().get("name"));
            List<List<String>> thumbnails = thumbnails();
            assertEquals(2, thumbnails.size());
            assertEquals(1, thumbnails.stream().filter(c -> c.contains("-ss")).count());
            assertEquals(1, thumbnails.stream().filter(c -> !c.contains("-ss")).count());
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("img.png", "EMPTY")));
            assertTrue(e.getMessage().contains("img.png"), e.getMessage());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void whatIsNeitherAnImageNorAVideoIsFiltered() throws Exception {
        FfmpegThumbnail action = action(new JsonObject());
        action.beforeAll();
        try {
            assertEquals(List.of(), action.doProcess(file("notes.txt", "words")));
            assertEquals(List.of(), calls(), "ffmpeg is not even looked for");
            assertEquals(List.of(), action.doProcess(file("song.mp4", "NO VIDEO")), "no video stream");
            assertEquals(0, workFiles(), "its working folder is gone");
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aHeicPhotoNeedsFfmpeg71() throws Exception {
        Files.createDirectories(state());
        Files.writeString(state().resolve("version"), "n7.0.2");
        FfmpegThumbnail action = action(new JsonObject());
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("photo.heic", "pixels")));
            assertEquals("ffmpeg.thumbnail: photo.heic is a HEIC photo, which needs ffmpeg 7.1 or later; the"
                    + " ffmpeg found is version n7.0.2", e.getMessage());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void aFailureGivesTheEndOfTheErrorOutput() throws Exception {
        FfmpegThumbnail action = action(new JsonObject());
        action.beforeAll();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> action.doProcess(file("broken.png", "FAIL")));
            assertTrue(e.getMessage().startsWith("ffmpeg.thumbnail: ffmpeg failed on broken.png (code 1): "),
                    e.getMessage());
            assertEquals(0, workFiles());
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void theDryRunNamesTheThumbnailsAndFiltersTheRest() throws Exception {
        FfmpegThumbnail action = action(new JsonObject());

        assertEquals("photo-thumb.jpg", action.dryRun(file("photo.HEIC", "pixels")).orElseThrow().getFirst()
                .getMetadatas().display().get("name"));
        assertEquals("clip-video-thumb.jpg", action.dryRun(file("clip.mp4", "frames")).orElseThrow().getFirst()
                .getMetadatas().display().get("name"));
        assertEquals(List.of(), action.dryRun(file("notes.txt", "words")).orElseThrow());
        assertEquals(List.of(), calls());
    }
}
