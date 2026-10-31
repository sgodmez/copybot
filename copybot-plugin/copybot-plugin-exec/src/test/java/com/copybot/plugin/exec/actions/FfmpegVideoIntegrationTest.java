package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.action.ItemProgress;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.exec.ffmpeg.FfmpegSetup;
import com.copybot.plugin.exec.process.ProcessRunner;
import com.copybot.plugin.exec.process.ProgramResolver;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ffmpeg.video with the real ffmpeg of the PATH; skipped when there is none (or it has no libx265). */
public class FfmpegVideoIntegrationTest {

    @TempDir
    Path tempDir;

    private static Path ffmpeg() throws Exception {
        Optional<Path> ffmpeg = ProgramResolver.resolve("ffmpeg");
        assumeTrue(ffmpeg.isPresent(), "no ffmpeg in the PATH");
        List<String> encoders = Collections.synchronizedList(new ArrayList<>());
        ProcessRunner.run(List.of(ffmpeg.get().toString(), "-hide_banner", "-encoders"), Duration.ofSeconds(30),
                null, encoders::add);
        assumeTrue(encoders.stream().anyMatch(line -> line.contains("libx265")), "ffmpeg without libx265");
        return ffmpeg.get();
    }

    @Test
    public void aRealVideoIsReducedIntoAnH265Mp4() throws Exception {
        Path ffmpeg = ffmpeg();
        // a phone video: a QuickTime file with the place of Apple's keys
        Path source = tempDir.resolve("clip.mov");
        ProcessRunner.Result made = ProcessRunner.run(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "2", "-c:v", "mpeg4", "-c:a", "pcm_s16le",
                "-metadata", "com.apple.quicktime.location.ISO6709=+48.8577+002.2950/",
                "-movflags", "use_metadata_tags", source.toString()), Duration.ofSeconds(60), null);
        assertEquals(0, made.exitCode(), made.stderrTail());

        JsonObject config = new JsonObject();
        config.addProperty("encoder", "cpu");
        config.addProperty("maxHeight", 120);
        config.addProperty("ffmpegPath", ffmpeg.toString());
        FfmpegVideo action = new FfmpegVideo(tempDir.resolve("work"), FfmpegSetup.Context::current);
        action.loadConfig(config);
        action.beforeAll();
        try {
            WorkItem item = new WorkItem(source);
            List<Integer> percents = Collections.synchronizedList(new ArrayList<>());
            WorkItem result = ItemProgress.callWith(status -> percents.add(status.actionPercent()),
                    () -> action.doProcess(item)).getFirst();

            assertEquals("clip.mp4", result.getMetadatas().display().get("name"));
            Path out = result.getContentLocation();
            assertTrue(Files.size(out) > 0);
            assertTrue(percents.contains(100), percents.toString());

            List<String> probe = Collections.synchronizedList(new ArrayList<>());
            ProcessRunner.run(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", out.toString()),
                    Duration.ofSeconds(30), probe::add);
            String info = String.join("\n", probe);
            assertTrue(info.contains("hevc") && info.contains("hvc1"), info);
            assertTrue(info.contains("160x120"), "reduced, even width: " + info);
            assertTrue(info.contains("aac"), info);
            assertTrue(info.contains("yuv420p10le"), "x265 Main10: " + info);
            assertTrue(info.contains("com.apple.quicktime.location.ISO6709") && info.contains("+48.8577+002.2950/"),
                    "the place kept: " + info);

            WorkItem text = new WorkItem(Files.writeString(tempDir.resolve("notes.mp4"), "no video here",
                    StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, () -> action.doProcess(text), "not a media file: an error");

            Path song = tempDir.resolve("song.mp4");
            ProcessRunner.run(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-f", "lavfi", "-i",
                    "sine=frequency=440", "-t", "1", "-c:a", "aac", song.toString()), Duration.ofSeconds(60), null);
            WorkItem audio = new WorkItem(song);
            assertSame(audio, action.doProcess(audio).getFirst(), "no video stream: unchanged");
            action.afterItem(item);
        } finally {
            action.afterAll();
        }
    }
}
