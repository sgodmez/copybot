package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.exec.ffmpeg.FfmpegSettings;

/**
 * The "actionConfig" of ffmpeg.video (spec exec-ffmpeg §5).
 *
 * @param codec          h265 or h264; absent: h265
 * @param quality        high, medium or small; absent: medium
 * @param maxHeight      the height a higher video is reduced to, its ratio kept; absent: not reduced
 * @param audio          aac (160 kb/s) or copy; absent: aac
 * @param encoder        auto (the first hardware encoder that works, else the processor), cpu, nvenc, qsv, amf,
 *                       videotoolbox or vaapi; absent: auto
 * @param timeoutSeconds the time limit for one item, 0: none; absent: 3600
 * @param ffmpegPath     the ffmpeg to run; absent: found by the plugin
 * @param downloadFfmpeg true: downloaded when it is not found (Windows x64)
 */
public record FfmpegVideoConfig(
        @AllowedValues({"h265", "h264"}) @DefaultValue("h265")
        String codec,

        @AllowedValues({"high", "medium", "small"}) @DefaultValue("medium")
        String quality,

        Integer maxHeight,

        @AllowedValues({"aac", "copy"}) @DefaultValue("aac")
        String audio,

        @AllowedValues({"auto", "cpu", "nvenc", "qsv", "amf", "videotoolbox", "vaapi"}) @DefaultValue("auto")
        String encoder,

        @DefaultValue("3600")
        Integer timeoutSeconds,

        @FilePath
        String ffmpegPath,

        @DefaultValue("false")
        Boolean downloadFfmpeg
) implements FfmpegSettings {

    public static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    public FfmpegVideoConfig {
        if (timeoutSeconds == null) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
