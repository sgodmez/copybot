package com.copybot.plugin.ffmpeg.ffmpeg;

/**
 * The fields every ffmpeg action has (spec exec-ffmpeg §4): its configuration record declares them, with
 * {@code @FilePath} on {@code ffmpegPath}, and implements this interface.
 */
public interface FfmpegSettings {

    /** The ffmpeg to run: a path, or a name looked up in the PATH; null or blank: found by the plugin. */
    String ffmpegPath();

    /** True: downloaded when it is not found (Windows x64); null: false. */
    Boolean downloadFfmpeg();

    /** Settings given by hand. */
    static FfmpegSettings of(String ffmpegPath, Boolean downloadFfmpeg) {
        return new Given(ffmpegPath, downloadFfmpeg);
    }

    /** Settings given by hand. */
    record Given(String ffmpegPath, Boolean downloadFfmpeg) implements FfmpegSettings {
    }
}
