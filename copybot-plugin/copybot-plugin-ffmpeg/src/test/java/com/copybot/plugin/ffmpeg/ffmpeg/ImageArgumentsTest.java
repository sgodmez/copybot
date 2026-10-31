package com.copybot.plugin.ffmpeg.ffmpeg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The arguments of ffmpeg.image and ffmpeg.thumbnail (spec exec-ffmpeg §6, §7). */
public class ImageArgumentsTest {

    private static final Path IN = Path.of("in", "photo.heic");
    private static final Path OUT = Path.of("out", "photo.jpg");

    @Test
    public void theQualityFrom1To100IsTheJpegScaleFrom31To2() {
        assertEquals(31, ImageArguments.qscale(1));
        assertEquals(2, ImageArguments.qscale(100));
        assertEquals(5, ImageArguments.qscale(90), "the default of ffmpeg.image");
        assertEquals(6, ImageArguments.qscale(85), "the thumbnails");
        assertEquals(17, ImageArguments.qscale(50));
        assertEquals(31, ImageArguments.qscale(0), "below 1: 1");
        assertEquals(2, ImageArguments.qscale(150), "above 100: 100");
        for (int quality = 2; quality <= 100; quality++) {
            assertTrue(ImageArguments.qscale(quality) <= ImageArguments.qscale(quality - 1), "never worse");
        }
    }

    @Test
    public void anImageIsOneJpegFrameOfTheLargestPicture() {
        // no -map: ffmpeg takes the largest picture (a HEIC photo: its tiles merged, not its small preview)
        assertEquals(List.of("-hide_banner", "-nostdin", "-y", "-i", IN.toString(), "-frames:v", "1",
                        "-q:v", "5", "-update", "1", OUT.toString()),
                ImageArguments.image(IN, OUT, 90, null));
    }

    @Test
    public void theLongSideIsOnlyReduced() {
        List<String> arguments = ImageArguments.image(IN, OUT, 90, 1600);
        assertEquals("scale=w='min(iw,1600)':h='min(ih,1600)':force_original_aspect_ratio=decrease,setsar=1",
                arguments.get(arguments.indexOf("-vf") + 1));
        assertTrue(arguments.indexOf("-vf") > arguments.indexOf("-i"));
        assertFalse(ImageArguments.image(IN, OUT, 90, 0).contains("-vf"), "0: not reduced");
        assertFalse(ImageArguments.image(IN, OUT, 90, -3).contains("-vf"));
    }

    @Test
    public void aFrameOfAVideoIsTakenAfterASeekFromItsFirstVideoStream() {
        Path video = Path.of("in", "clip.mov");
        Path thumb = Path.of("out", "clip-thumb.jpg");
        assertEquals(List.of("-hide_banner", "-nostdin", "-y", "-ss", "3.000", "-i", video.toString(),
                        "-map", "0:V:0", "-frames:v", "1",
                        "-vf", "scale=w='min(iw,320)':h='min(ih,320)':force_original_aspect_ratio=decrease,setsar=1",
                        "-q:v", "6", "-update", "1", thumb.toString()),
                ImageArguments.videoFrame(video, thumb, 3_000_000, 320, 85));
        assertFalse(ImageArguments.videoFrame(video, thumb, 0, 320, 85).contains("-ss"), "no seek at 0");
        assertEquals("0.250", ImageArguments.videoFrame(video, thumb, 250_000, 320, 85).get(4));
    }

    @Test
    public void theFrameIsAtATenthOfTheDurationAtMostTenSeconds() {
        assertEquals(3_000_000, ImageArguments.seekMicros(30_000_000));
        assertEquals(10_000_000, ImageArguments.seekMicros(100_000_000));
        assertEquals(10_000_000, ImageArguments.seekMicros(3_600_000_000L));
        assertEquals(50_000, ImageArguments.seekMicros(500_000));
        assertEquals(0, ImageArguments.seekMicros(0), "unknown duration: the first frame");
        assertEquals(0, ImageArguments.seekMicros(-1));
    }

    @Test
    public void theInspectionOnlyReadsTheInput() {
        assertEquals(List.of("-hide_banner", "-nostdin", "-i", IN.toString()), ImageArguments.inspect(IN));
    }
}
