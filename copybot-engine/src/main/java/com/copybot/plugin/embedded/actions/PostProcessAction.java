package com.copybot.plugin.embedded.actions;

import com.copybot.engine.pipeline.PostProcessConfig;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IRunEndAction;
import com.copybot.plugin.api.action.RunEndContext;
import com.copybot.plugin.api.action.RunReport;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * post.process: launches a program at the end of a copy (formerly the "postProcess" block), without waiting for it:
 * the directories the copy created replace {@link PostProcessConfig#CREATED_DIRS}, parents first. A failed launch is
 * a warning of the run.
 */
public class PostProcessAction extends AbstractActionWithConfig<PostProcessConfig> implements IRunEndAction {

    public static final String CODE = "post.process";

    private static final System.Logger LOGGER = System.getLogger(PostProcessAction.class.getName());

    /** Starts a process; a seam for the tests. */
    public interface Starter {
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

    private final Starter starter;

    public PostProcessAction() {
        this(PROCESS_STARTER);
    }

    public PostProcessAction(Starter starter) {
        this.starter = starter;
    }

    @Override
    protected Class<PostProcessConfig> getConfigClass() {
        return PostProcessConfig.class;
    }

    /** The program is only launched, never waited for. */
    @Override
    public Duration defaultTimeout() {
        return Duration.ofSeconds(30);
    }

    @Override
    public void runEnded(RunReport report, RunEndContext context) {
        launch(report.createdDirectories()).ifPresent(context::warn);
    }

    /**
     * Launches the program, unless there is none or it needs the created directories and there are none.
     *
     * @return the warning of a failed launch
     */
    public Optional<String> launch(Collection<Path> createdDirectories) {
        PostProcessConfig config = getConfig();
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
        // the program only: its arguments may hold the value of a ${secret:} or ${env:} reference
        LOGGER.log(System.Logger.Level.INFO, "Post-processing: {0} ({1} arguments)", config.program(),
                commandLine.size() - 1);
        try {
            starter.start(commandLine, directoryOf(config.program()));
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Post-processing failed: " + config.program(), e);
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
