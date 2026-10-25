package com.copybot.engine;

import com.copybot.engine.pipeline.PostProcessConfig;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Launches the program of the pipeline at the end of a copy (spec post-process), without waiting for it: the
 * directories the copy created replace {@link PostProcessConfig#CREATED_DIRS}, parents first.
 */
final class PostProcess {

    private static final System.Logger LOGGER = System.getLogger(PostProcess.class.getName());

    /** Starts a process; visible for tests. */
    interface Starter {
        void start(List<String> commandLine, Path directory) throws IOException;
    }

    /**
     * The real start: its output ignored, its input closed at once (a program reading it gets the end, never
     * waits), never waited for.
     */
    private static final Starter PROCESS_STARTER = (commandLine, directory) -> {
        ProcessBuilder builder = new ProcessBuilder(commandLine)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.start().getOutputStream().close();
    };

    private final PostProcessConfig config;
    private final Starter starter;

    PostProcess(PostProcessConfig config, Starter starter) {
        this.config = config;
        this.starter = starter;
    }

    static PostProcess of(PostProcessConfig config) {
        return new PostProcess(config, PROCESS_STARTER);
    }

    /**
     * Launches the program, unless there is none or it needs the created directories and there are none.
     *
     * @return the warning of a failed launch
     */
    Optional<String> run(Collection<Path> createdDirectories) {
        if (config == null || !config.isSet()) {
            return Optional.empty();
        }
        List<String> dirs = createdDirectories.stream().sorted().map(Path::toString).toList();
        List<String> commandLine = new ArrayList<>();
        commandLine.add(config.program());
        for (String arg : config.args()) {
            if (arg.strip().equals(PostProcessConfig.CREATED_DIRS)) {
                if (dirs.isEmpty()) {
                    return Optional.empty(); // nothing to process
                }
                commandLine.addAll(dirs);
            } else {
                commandLine.add(arg);
            }
        }
        LOGGER.log(System.Logger.Level.INFO, "Post-processing: {0}", commandLine);
        try {
            starter.start(commandLine, directoryOf(config.program()));
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Post-processing failed: " + commandLine, e);
            return Optional.of(ResourcesEngine.getString("post-process.failed", config.program(),
                    e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** The directory of a program given by its path; null (the current one) for a command found on the PATH. */
    private static Path directoryOf(String program) {
        Path path = Path.of(program);
        return path.isAbsolute() ? path.getParent() : null;
    }
}
