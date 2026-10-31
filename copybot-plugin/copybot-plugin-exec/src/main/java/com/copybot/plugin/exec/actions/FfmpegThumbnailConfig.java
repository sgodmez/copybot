package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.exec.ffmpeg.FfmpegSettings;

/**
 * The "actionConfig" of ffmpeg.thumbnail (spec exec-ffmpeg §7).
 *
 * @param size           the long side of the thumbnail at most, in pixels (a smaller picture is not enlarged);
 *                       absent or below 1: 320
 * @param timeoutSeconds the time limit for one item, 0: none; absent: 600
 * @param ffmpegPath     the ffmpeg to run; absent: found by the plugin
 * @param downloadFfmpeg true: downloaded when it is not found (Windows x64)
 */
public record FfmpegThumbnailConfig(
        @DefaultValue("320")
        Integer size,

        @DefaultValue("600")
        Integer timeoutSeconds,

        @FilePath
        String ffmpegPath,

        @DefaultValue("false")
        Boolean downloadFfmpeg
) implements FfmpegSettings {

    public static final int DEFAULT_SIZE = 320;
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    public FfmpegThumbnailConfig {
        if (size == null || size < 1) {
            size = DEFAULT_SIZE;
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
