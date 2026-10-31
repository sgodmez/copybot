package com.copybot.plugin.exec.ffmpeg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The arguments of ffmpeg for ffmpeg.image and ffmpeg.thumbnail (spec exec-ffmpeg §6, §7): one JPEG frame, reduced
 * so that its long side is at most a size (never enlarged), turned as the source asks where ffmpeg does it ({@code -autorotate} is on by default: the rotation of a HEIC
 * photo is applied from ffmpeg 7.1, the EXIF orientation of a JPEG, PNG or TIFF depends on the ffmpeg build).
 */
public final class ImageArguments {

    /** The quality of ffmpeg.image when none is given. */
    public static final int DEFAULT_QUALITY = 90;
    /** The quality of the thumbnails. */
    public static final int THUMBNAIL_QUALITY = 85;
    /** The latest frame of a video taken as its thumbnail. */
    static final long MAX_SEEK_MICROS = 10_000_000;

    private ImageArguments() {
    }

    /**
     * The JPEG scale of ffmpeg ({@code -q:v}, 2 the best, 31 the worst) of a quality from 1 (the worst) to 100 (the
     * best); a quality out of these bounds is taken as the nearest one.
     */
    public static int qscale(int quality) {
        int bounded = Math.clamp(quality, 1, 100);
        return 31 - (int) Math.round((bounded - 1) * 29 / 99.0);
    }

    /**
     * The arguments writing {@code in} into the JPEG {@code out}. No stream is chosen: ffmpeg takes the largest
     * picture, so the photo of a HEIC file (its tiles put together by ffmpeg 7.1 and later), not its preview.
     *
     * @param maxSize the long side the picture is reduced to when it is larger; null or below 1: not reduced
     */
    public static List<String> image(Path in, Path out, int quality, Integer maxSize) {
        List<String> arguments = new ArrayList<>(List.of("-hide_banner", "-nostdin", "-y", "-i", in.toString(),
                "-frames:v", "1"));
        if (maxSize != null && maxSize >= 1) {
            arguments.addAll(List.of("-vf", scale(maxSize)));
        }
        arguments.addAll(jpeg(out, quality));
        return arguments;
    }

    /**
     * The arguments writing the frame of the video {@code in} at {@code seekMicros} into the JPEG {@code out}, from
     * its first real video stream (not a cover picture), its long side at most {@code size}.
     */
    public static List<String> videoFrame(Path in, Path out, long seekMicros, int size, int quality) {
        List<String> arguments = new ArrayList<>(List.of("-hide_banner", "-nostdin", "-y"));
        if (seekMicros > 0) {
            // before the input: a fast seek, decoding from the key frame before it
            arguments.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", seekMicros / 1_000_000.0)));
        }
        arguments.addAll(List.of("-i", in.toString(), "-map", "0:V:0", "-frames:v", "1", "-vf", scale(size)));
        arguments.addAll(jpeg(out, quality));
        return arguments;
    }

    /** The arguments reading the header of {@code in} only: its duration is in the error output (exit 1). */
    public static List<String> inspect(Path in) {
        return List.of("-hide_banner", "-nostdin", "-i", in.toString());
    }

    /** Where the thumbnail of a video is taken: at a tenth of its duration, at most 10 s; 0 when it is unknown. */
    public static long seekMicros(long durationMicros) {
        return durationMicros <= 0 ? 0 : Math.min(durationMicros / 10, MAX_SEEK_MICROS);
    }

    /**
     * The long side at most {@code size}, the ratio kept, never enlarged (the box is the picture itself when it is
     * smaller); square pixels, as a JPEG is shown.
     */
    private static String scale(int size) {
        return "scale=w='min(iw," + size + ")':h='min(ih," + size + ")':force_original_aspect_ratio=decrease"
                + ",setsar=1";
    }

    /** One JPEG picture of this quality into {@code out} ({@code -update}: a file name, no sequence). */
    private static List<String> jpeg(Path out, int quality) {
        return List.of("-q:v", String.valueOf(qscale(quality)), "-update", "1", out.toString());
    }
}
