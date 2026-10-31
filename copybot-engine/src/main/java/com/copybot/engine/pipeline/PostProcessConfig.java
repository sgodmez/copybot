package com.copybot.engine.pipeline;

import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.api.config.Required;

import java.util.List;
import java.util.Objects;

/**
 * The "actionConfig" of the embedded run-end action post.process (formerly the "postProcess" block): the program
 * launched at the end of a copy. The annotations describe the fields to the pipeline editor.
 *
 * @param command the program; absent or blank: nothing is launched
 * @param args    its arguments, passed as they are; one equal to {@link #CREATED_DIRS} (spaces around ignored) stands
 *                for the directories the copy created, one argument each
 */
public record PostProcessConfig(
        @Required @FilePath
        String command,

        List<String> args
) {

    public static final String CREATED_DIRS = "{createdDirs}";

    public PostProcessConfig {
        args = args == null ? List.of() : args.stream().filter(Objects::nonNull).toList();
    }

    public boolean isSet() {
        return !program().isEmpty();
    }

    /** The command without its surrounding spaces and quotes (Explorer's "copy as path" quotes it), "" when absent. */
    public String program() {
        return unquote(command);
    }

    public static String unquote(String command) {
        String program = command == null ? "" : command.strip();
        if (program.length() >= 2 && program.startsWith("\"") && program.endsWith("\"")) {
            program = program.substring(1, program.length() - 1).strip();
        }
        return program;
    }
}
