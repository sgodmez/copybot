package com.copybot.plugin.ffmpeg.actions;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.ffmpeg.ffmpeg.FfmpegSettings;
import com.copybot.plugin.ffmpeg.ffmpeg.ImageArguments;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The "actionConfig" of ffmpeg.image (spec exec-ffmpeg §6).
 *
 * @param quality        the JPEG quality, 1 to 100 (out of these bounds: the nearest); absent: 90
 * @param maxSize        the long side a larger picture is reduced to, its ratio kept; absent: not reduced
 * @param onlyExtensions the extensions converted (case and dot ignored), among the images ffmpeg reads; absent:
 *                       heic and heif; empty: every image ffmpeg reads
 * @param timeoutSeconds the time limit for one item, 0: none; absent: 600
 * @param ffmpegPath     the ffmpeg to run; absent: found by the plugin
 * @param downloadFfmpeg true: downloaded when it is not found (Windows x64)
 */
public record FfmpegImageConfig(
        @DefaultValue("90")
        Integer quality,

        Integer maxSize,

        @DefaultValue("[\"heic\",\"heif\"]")
        List<String> onlyExtensions,

        @DefaultValue("600")
        Integer timeoutSeconds,

        @FilePath
        String ffmpegPath,

        @DefaultValue("false")
        Boolean downloadFfmpeg
) implements FfmpegSettings {

    public static final int DEFAULT_TIMEOUT_SECONDS = 600;
    public static final List<String> DEFAULT_EXTENSIONS = List.of("heic", "heif");

    public FfmpegImageConfig {
        if (quality == null) {
            quality = ImageArguments.DEFAULT_QUALITY;
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        onlyExtensions = onlyExtensions == null ? DEFAULT_EXTENSIONS : onlyExtensions.stream()
                .filter(Objects::nonNull)
                .map(extension -> {
                    String bare = extension.strip();
                    while (bare.startsWith(".")) {
                        bare = bare.substring(1);
                    }
                    return bare.toLowerCase(Locale.ROOT);
                })
                .filter(extension -> !extension.isEmpty())
                .toList();
    }
}
