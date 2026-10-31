package com.copybot.plugin.exec.ffmpeg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The ffmpeg of a run: found once, shared by its ffmpeg steps.
 *
 * @param executable the executable, absolute
 * @param version    its version
 * @param launch     the command running it, before its arguments (the executable itself, outside the tests)
 */
public record Ffmpeg(Path executable, FfmpegVersion version, List<String> launch) {

    public Ffmpeg {
        launch = List.copyOf(launch);
    }

    /** The command line running ffmpeg with these arguments, for the {@code ProcessRunner}. */
    public List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>(launch);
        command.addAll(arguments);
        return command;
    }
}
