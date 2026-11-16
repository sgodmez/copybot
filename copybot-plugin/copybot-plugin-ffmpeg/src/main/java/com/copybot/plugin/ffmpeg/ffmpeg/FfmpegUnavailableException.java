package com.copybot.plugin.ffmpeg.ffmpeg;

/** ffmpeg cannot be used in this run: not found, not downloaded, or not working. The text says why. */
public class FfmpegUnavailableException extends Exception {

    private final transient FfmpegText text;

    public FfmpegUnavailableException(FfmpegText text) {
        super(text.key() + text.args());
        this.text = text;
    }

    public FfmpegUnavailableException(FfmpegText text, Throwable cause) {
        this(text);
        initCause(cause);
    }

    public FfmpegText text() {
        return text;
    }
}
