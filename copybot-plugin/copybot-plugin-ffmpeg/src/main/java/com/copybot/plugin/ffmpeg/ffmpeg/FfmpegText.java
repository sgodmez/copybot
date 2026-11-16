package com.copybot.plugin.ffmpeg.ffmpeg;

import java.util.Arrays;
import java.util.List;

/**
 * A text for the user, translated by the action with the bundle of the plugin.
 *
 * @param key  the key under {@code plugin.ffmpeg.}
 * @param args its arguments; none: the text is not a MessageFormat pattern
 */
public record FfmpegText(String key, List<String> args) {

    public FfmpegText {
        args = List.copyOf(args);
    }

    public static FfmpegText of(String key, Object... args) {
        return new FfmpegText(key, Arrays.stream(args).map(String::valueOf).toList());
    }
}
