package com.copybot.plugin.exec.ffmpeg;

import com.copybot.plugin.exec.ffmpeg.VideoArguments.Codec;
import com.copybot.plugin.exec.ffmpeg.VideoArguments.Encoder;
import com.copybot.plugin.exec.ffmpeg.VideoArguments.Quality;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The arguments of ffmpeg.video (spec exec-ffmpeg §5), option by option and encoder by encoder. */
public class VideoArgumentsTest {

    private static final Path IN = Path.of("in", "clip.mov");
    private static final Path OUT = Path.of("out", "clip.mp4");

    private static List<String> transcode(Codec codec, Quality quality, Integer maxHeight, boolean copyAudio,
                                          Encoder encoder) {
        return new VideoArguments(codec, quality, maxHeight, copyAudio, encoder).transcode(IN, OUT);
    }

    /** The arguments after {@code -c:v}, up to the audio ones. */
    private static List<String> video(List<String> arguments) {
        return arguments.subList(arguments.indexOf("-c:v"), arguments.indexOf("-c:a"));
    }

    @Test
    public void theDefaultIsH265ByTheProcessorInAnMp4ReadByApple() {
        assertEquals(List.of("-hide_banner", "-nostdin", "-y", "-i", IN.toString(),
                "-map", "0:V:0", "-map", "0:a?", "-map_metadata", "0",
                "-c:v", "libx265", "-preset", "medium", "-crf", "26", "-pix_fmt", "yuv420p10le",
                "-x265-params", "log-level=error",
                "-c:a", "aac", "-b:a", "160k",
                "-tag:v", "hvc1", "-movflags", "+faststart+use_metadata_tags",
                "-progress", "pipe:2", "-nostats", OUT.toString()),
                transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.CPU));
    }

    @Test
    public void theQualityIsACrfOf22To30ForX265And4LessForX264() {
        assertEquals("22", after(transcode(Codec.H265, Quality.HIGH, null, false, Encoder.CPU), "-crf"));
        assertEquals("26", after(transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.CPU), "-crf"));
        assertEquals("30", after(transcode(Codec.H265, Quality.SMALL, null, false, Encoder.CPU), "-crf"));
        assertEquals("18", after(transcode(Codec.H264, Quality.HIGH, null, false, Encoder.CPU), "-crf"));
        assertEquals("22", after(transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.CPU), "-crf"));
        assertEquals("26", after(transcode(Codec.H264, Quality.SMALL, null, false, Encoder.CPU), "-crf"));
    }

    @Test
    public void h264IsX264In8BitsWithoutTheHevcTag() {
        List<String> arguments = transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.CPU);
        assertEquals(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "22", "-pix_fmt", "yuv420p"),
                video(arguments));
        assertFalse(arguments.contains("-tag:v"), "hvc1 is for H.265 only");
        assertTrue(arguments.containsAll(List.of("-movflags", "+faststart+use_metadata_tags", "-map_metadata")));
    }

    @Test
    public void eachHardwareEncoderHasItsOwnQualityOption() {
        assertEquals(List.of("-c:v", "hevc_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "26", "-b:v", "0"),
                video(transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.NVENC)));
        assertEquals(List.of("-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "18", "-b:v", "0",
                        "-pix_fmt", "yuv420p"),
                video(transcode(Codec.H264, Quality.HIGH, null, false, Encoder.NVENC)));
        assertEquals(List.of("-c:v", "hevc_qsv", "-preset", "medium", "-global_quality", "30"),
                video(transcode(Codec.H265, Quality.SMALL, null, false, Encoder.QSV)));
        assertEquals(List.of("-c:v", "h264_qsv", "-preset", "medium", "-global_quality", "22", "-pix_fmt", "nv12"),
                video(transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.QSV)));
        assertEquals(List.of("-c:v", "hevc_amf", "-rc", "cqp", "-qp_i", "22", "-qp_p", "22"),
                video(transcode(Codec.H265, Quality.HIGH, null, false, Encoder.AMF)));
        assertEquals(List.of("-c:v", "h264_amf", "-rc", "cqp", "-qp_i", "26", "-qp_p", "26", "-qp_b", "26",
                        "-pix_fmt", "yuv420p"),
                video(transcode(Codec.H264, Quality.SMALL, null, false, Encoder.AMF)));
        assertEquals(List.of("-c:v", "hevc_vaapi", "-rc_mode", "CQP", "-qp", "26"),
                video(transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.VAAPI)));
        assertEquals(List.of("-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", "22"),
                video(transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.VAAPI)));
    }

    @Test
    public void h264Is8Bits420WithEveryEncoderButVaapiAndH265IsMain10OnTheProcessor() {
        // a 10-bit (HDR) source would make the hardware H.264 encoders fail, or give High 10 that few devices read
        for (Encoder encoder : List.of(Encoder.CPU, Encoder.NVENC, Encoder.AMF, Encoder.VIDEOTOOLBOX)) {
            assertEquals("yuv420p", after(transcode(Codec.H264, Quality.MEDIUM, null, false, encoder), "-pix_fmt"),
                    encoder.name());
        }
        assertEquals("nv12", after(transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.QSV), "-pix_fmt"));
        assertFalse(transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.VAAPI).contains("-pix_fmt"),
                "vaapi: format=nv12 before hwupload");
        // x265 Main10: HDR kept, never a 4:2:2 or 12-bit range extension
        assertEquals("yuv420p10le",
                after(transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.CPU), "-pix_fmt"));
        for (Encoder encoder : Encoder.AUTO_ORDER) {
            assertFalse(transcode(Codec.H265, Quality.MEDIUM, null, false, encoder).contains("-pix_fmt"),
                    encoder.name());
        }
    }

    @Test
    public void videoToolboxTakesAQualityWhereHigherIsBetter() {
        assertEquals(List.of("-c:v", "hevc_videotoolbox", "-q:v", "65"),
                video(transcode(Codec.H265, Quality.HIGH, null, false, Encoder.VIDEOTOOLBOX)));
        assertEquals("55", after(transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.VIDEOTOOLBOX), "-q:v"));
        assertEquals("45", after(transcode(Codec.H265, Quality.SMALL, null, false, Encoder.VIDEOTOOLBOX), "-q:v"));
        assertEquals(List.of("-c:v", "h264_videotoolbox", "-q:v", "60", "-pix_fmt", "yuv420p"),
                video(transcode(Codec.H264, Quality.MEDIUM, null, false, Encoder.VIDEOTOOLBOX)));
    }

    @Test
    public void everyEncoderKeepsTheMp4Options() {
        for (Encoder encoder : Encoder.values()) {
            if (encoder == Encoder.AUTO) {
                continue;
            }
            List<String> arguments = transcode(Codec.H265, Quality.MEDIUM, null, false, encoder);
            assertEquals("hvc1", after(arguments, "-tag:v"), encoder.name());
            assertEquals("+faststart+use_metadata_tags", after(arguments, "-movflags"), encoder.name());
            assertEquals("0", after(arguments, "-map_metadata"), encoder.name());
            assertEquals(List.of("-progress", "pipe:2", "-nostats", OUT.toString()),
                    arguments.subList(arguments.size() - 4, arguments.size()), encoder.name());
        }
    }

    @Test
    public void vaapiUploadsTheFramesToItsDevice() {
        List<String> arguments = transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.VAAPI);
        assertEquals(List.of("-hide_banner", "-nostdin", "-y", "-vaapi_device", "/dev/dri/renderD128", "-i"),
                arguments.subList(0, 6));
        assertEquals("format=nv12,hwupload", after(arguments, "-vf"));

        assertEquals("scale=-2:'min(720,trunc(ih/2)*2)',format=nv12,hwupload",
                after(transcode(Codec.H265, Quality.MEDIUM, 720, false, Encoder.VAAPI), "-vf"));
    }

    @Test
    public void aMaximumHeightOnlyReducesAndKeepsEvenDimensions() {
        List<String> arguments = transcode(Codec.H265, Quality.MEDIUM, 1080, false, Encoder.CPU);
        assertEquals("scale=-2:'min(1080,trunc(ih/2)*2)'", after(arguments, "-vf"));
        assertTrue(arguments.indexOf("-vf") < arguments.indexOf("-c:v"));

        assertEquals("scale=-2:'min(480,trunc(ih/2)*2)'",
                after(transcode(Codec.H265, Quality.MEDIUM, 481, false, Encoder.NVENC), "-vf"), "odd: made even");
        assertFalse(transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.CPU).contains("-vf"));
        assertFalse(transcode(Codec.H265, Quality.MEDIUM, 0, false, Encoder.CPU).contains("-vf"), "0: none");
        assertFalse(transcode(Codec.H265, Quality.MEDIUM, 1, false, Encoder.CPU).contains("-vf"), "1: none");
    }

    @Test
    public void theAudioIsAacAt160KbitsOrCopied() {
        assertEquals(List.of("-c:a", "aac", "-b:a", "160k"), audio(transcode(Codec.H265, Quality.MEDIUM, null,
                false, Encoder.CPU)));
        assertEquals(List.of("-c:a", "copy"), audio(transcode(Codec.H265, Quality.MEDIUM, null, true,
                Encoder.CPU)));
    }

    @Test
    public void autoIsNoEncoderOfItsOwn() {
        assertThrows(IllegalArgumentException.class,
                () -> transcode(Codec.H265, Quality.MEDIUM, null, false, Encoder.AUTO));
        assertThrows(IllegalArgumentException.class, () -> VideoArguments.probe(Codec.H265, Encoder.AUTO));
    }

    @Test
    public void theProbeEncodesOneBlackFrameToNothing() {
        assertEquals(List.of("-hide_banner", "-nostdin", "-f", "lavfi", "-i", "color=black:s=256x256",
                        "-frames:v", "1", "-c:v", "hevc_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "26", "-b:v", "0",
                        "-f", "null", "-"),
                VideoArguments.probe(Codec.H265, Encoder.NVENC));
        assertEquals(List.of("-hide_banner", "-nostdin", "-vaapi_device", "/dev/dri/renderD128", "-f", "lavfi",
                        "-i", "color=black:s=256x256", "-frames:v", "1", "-vf", "format=nv12,hwupload",
                        "-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", "22", "-f", "null", "-"),
                VideoArguments.probe(Codec.H264, Encoder.VAAPI));
    }

    @Test
    public void theAutoOrderAndTheHardwareEncoders() {
        assertEquals(List.of(Encoder.NVENC, Encoder.QSV, Encoder.AMF, Encoder.VIDEOTOOLBOX, Encoder.VAAPI),
                Encoder.AUTO_ORDER);
        assertFalse(Encoder.CPU.hardware());
        assertFalse(Encoder.AUTO.hardware());
        Encoder.AUTO_ORDER.forEach(encoder -> assertTrue(encoder.hardware(), encoder.name()));
    }

    @Test
    public void theValuesOfTheConfigurationAreParsed() {
        assertEquals(Codec.H264, VideoArguments.parse(Codec.class, " H264 ").orElseThrow());
        assertEquals(Encoder.VIDEOTOOLBOX, VideoArguments.parse(Encoder.class, "videotoolbox").orElseThrow());
        assertEquals(Quality.SMALL, VideoArguments.parse(Quality.class, "small").orElseThrow());
        assertTrue(VideoArguments.parse(Codec.class, "av1").isEmpty());
        assertEquals("h265", Codec.H265.code());
    }

    private static List<String> audio(List<String> arguments) {
        int from = arguments.indexOf("-c:a");
        int to = arguments.contains("-tag:v") ? arguments.indexOf("-tag:v") : arguments.indexOf("-movflags");
        return arguments.subList(from, to);
    }

    private static String after(List<String> arguments, String option) {
        int index = arguments.indexOf(option);
        assertTrue(index >= 0, option + " in " + arguments);
        return arguments.get(index + 1);
    }
}
