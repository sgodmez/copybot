package com.copybot.plugin.exec.ffmpeg;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of an ffmpeg, read from {@code ffmpeg -version} ("ffmpeg version n8.1-7-g1234567 Copyright...").
 *
 * @param text        the version as ffmpeg gives it; "" when it gives none
 * @param major       its major number; -1 when unknown
 * @param minor       its minor number (0 when it has none); -1 when unknown
 * @param development a build of the development branch ("N-121234-g...", "git-...", "2024-03-14-git-..."): taken
 *                    as newer than every release ({@link #atLeast} is true), the development branch having what
 *                    the releases have
 */
public record FfmpegVersion(String text, int major, int minor, boolean development) {

    private static final Pattern LINE = Pattern.compile("ffmpeg version (\\S+)");
    /** A dated build of the development branch ("2024-03-14-git-abc123-full_build-www.gyan.dev"). */
    private static final Pattern DATED = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-git-");
    private static final Pattern NUMBER = Pattern.compile("n?(\\d{1,6})(?:\\.(\\d{1,6}))?(?!\\d)");

    /** The version in the output of {@code ffmpeg -version}; unknown when it holds none. */
    public static FfmpegVersion parse(String output) {
        Matcher line = LINE.matcher(output == null ? "" : output);
        if (!line.find()) {
            return new FfmpegVersion("", -1, -1, false);
        }
        String text = line.group(1);
        if (DATED.matcher(text).lookingAt()) {
            return new FfmpegVersion(text, -1, -1, true); // not version 2024
        }
        Matcher number = NUMBER.matcher(text);
        if (number.lookingAt()) {
            return new FfmpegVersion(text, Integer.parseInt(number.group(1)),
                    number.group(2) == null ? 0 : Integer.parseInt(number.group(2)), false);
        }
        return new FfmpegVersion(text, -1, -1, text.startsWith("N-") || text.startsWith("git-"));
    }

    /** True when this version is at least major.minor; false when it is unknown. */
    public boolean atLeast(int major, int minor) {
        if (development) {
            return true;
        }
        if (this.major < 0) {
            return false;
        }
        return this.major != major ? this.major > major : this.minor >= minor;
    }
}
