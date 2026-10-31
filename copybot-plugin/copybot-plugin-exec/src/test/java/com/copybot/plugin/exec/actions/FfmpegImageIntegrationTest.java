package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.ffmpeg.FfmpegVersion;
import com.copybot.plugin.exec.process.ProcessRunner;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ffmpeg.image and ffmpeg.thumbnail with the real ffmpeg of the PATH; skipped when there is none. No HEIC photo is
 * made here (ffmpeg writes none): with an ffmpeg before 7.1, a HEIC item is checked to be refused.
 */
public class FfmpegImageIntegrationTest {

    private static final Instant CAPTURE = Instant.parse("2023-08-15T09:30:00Z");

    @TempDir
    Path tempDir;

    private static Path ffmpeg() {
        Optional<Path> ffmpeg = ProgramResolver.resolve("ffmpeg");
        assumeTrue(ffmpeg.isPresent(), "no ffmpeg in the PATH");
        return ffmpeg.get();
    }

    private static void make(Path ffmpeg, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-y"));
        command.addAll(List.of(arguments));
        ProcessRunner.Result made = ProcessRunner.run(command, Duration.ofSeconds(60), null);
        assertEquals(0, made.exitCode(), made.stderrTail());
    }

    /** What ffmpeg says of the file: its streams. */
    private static String info(Path ffmpeg, Path file) throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ProcessRunner.run(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", file.toString()),
                Duration.ofSeconds(30), lines::add);
        return String.join("\n", lines);
    }

    @Test
    public void aRealImageBecomesAReducedJpegDatedByItsCapture() throws Exception {
        Path ffmpeg = ffmpeg();
        Path source = tempDir.resolve("shot.png");
        make(ffmpeg, "-f", "lavfi", "-i", "testsrc=size=401x301", "-frames:v", "1", source.toString());

        JsonObject config = new JsonObject();
        JsonArray extensions = new JsonArray();
        extensions.add("png");
        extensions.add("heic");
        config.add("onlyExtensions", extensions);
        config.addProperty("maxSize", 200);
        config.addProperty("ffmpegPath", ffmpeg.toString());
        FfmpegImage action = new FfmpegImage(tempDir.resolve("work"), FfmpegSetup.Context::current);
        action.loadConfig(config);
        action.beforeAll();
        try {
            WorkItem item = new WorkItem(source);
            item.getMetadatas().setTime(WorkItemMetadata.CAPTURE_DATE, CAPTURE);
            WorkItem result = action.doProcess(item).getFirst();

            assertEquals("shot.jpg", result.getMetadatas().display().get("name"));
            Path out = result.getContentLocation();
            assertEquals(CAPTURE, Files.getLastModifiedTime(out).toInstant());
            String info = info(ffmpeg, out);
            assertTrue(info.contains("mjpeg"), info);
            assertTrue(info.contains("200x150"), "the long side at 200, the ratio kept: " + info);
            assertTrue(info.contains("SAR 1:1"), "square pixels: " + info);

            WorkItem text = new WorkItem(Files.writeString(tempDir.resolve("notes.png"), "no image here",
                    StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, () -> action.doProcess(text), "not an image: an error");

            FfmpegVersion version = FfmpegVersion.parse(versionOutput(ffmpeg));
            if (!version.atLeast(7, 1)) {
                WorkItem heic = new WorkItem(Files.writeString(tempDir.resolve("photo.heic"), "heic",
                        StandardCharsets.UTF_8));
                IllegalStateException e = assertThrows(IllegalStateException.class, () -> action.doProcess(heic));
                assertTrue(e.getMessage().contains(version.text()), e.getMessage());
            }
            action.afterItem(item);
        } finally {
            action.afterAll();
        }
    }

    @Test
    public void realThumbnailsOfAnImageAndOfAVideo() throws Exception {
        Path ffmpeg = ffmpeg();
        Path photo = tempDir.resolve("photo.jpg");
        make(ffmpeg, "-f", "lavfi", "-i", "testsrc=size=640x480", "-frames:v", "1", photo.toString());
        Path clip = tempDir.resolve("clip.avi");
        make(ffmpeg, "-f", "lavfi", "-i", "testsrc=size=240x320:rate=10", "-t", "3", "-c:v", "mpeg4",
                clip.toString());
        Path song = tempDir.resolve("song.mkv");
        make(ffmpeg, "-f", "lavfi", "-i", "sine=frequency=440", "-t", "1", "-c:a", "flac", song.toString());

        JsonObject config = new JsonObject();
        config.addProperty("size", 160);
        config.addProperty("ffmpegPath", ffmpeg.toString());
        FfmpegThumbnail action = new FfmpegThumbnail(tempDir.resolve("work"), FfmpegSetup.Context::current);
        action.loadConfig(config);
        action.beforeAll();
        try {
            WorkItem image = action.doProcess(new WorkItem(photo)).getFirst();
            assertEquals("photo-thumb.jpg", image.getMetadatas().display().get("name"));
            assertTrue(info(ffmpeg, image.getContentLocation()).contains("160x120"),
                    info(ffmpeg, image.getContentLocation()));

            WorkItem video = action.doProcess(new WorkItem(clip)).getFirst();
            assertEquals("clip-video-thumb.jpg", video.getMetadatas().display().get("name"));
            String info = info(ffmpeg, video.getContentLocation());
            assertTrue(info.contains("mjpeg") && info.contains("120x160"), "portrait kept: " + info);

            assertEquals(List.of(), action.doProcess(new WorkItem(song)), "no video stream: filtered");
        } finally {
            action.afterAll();
        }
    }

    private static String versionOutput(Path ffmpeg) throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ProcessRunner.run(List.of(ffmpeg.toString(), "-version"), Duration.ofSeconds(30), null, lines::add);
        return String.join("\n", lines);
    }
}
