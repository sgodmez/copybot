package com.copybot.plugin.exec.actions;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.FilePath;
import com.copybot.plugin.api.config.Required;

import java.util.List;
import java.util.Objects;

/**
 * The "actionConfig" of exec.process (spec exec-ffmpeg §3).
 *
 * @param program         the executable: a path, or a name looked up in the PATH
 * @param arguments       its arguments, with the variables {in}, {out}, {name} (without extension) and {ext}; they
 *                        may hold resolved secrets and are never put in a message or a log
 * @param outputExtension the extension of the file the program writes (e.g. "jpg"); absent or blank: the item's
 * @param successCodes    the exit codes of a success; absent or empty: 0
 * @param timeoutSeconds  the time limit for one item, 0: none; absent: 600
 */
public record ExecConfig(
        @Required @FilePath
        String program,

        List<String> arguments,

        String outputExtension,

        List<Integer> successCodes,

        @DefaultValue("600")
        Integer timeoutSeconds
) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    public ExecConfig {
        arguments = arguments == null ? List.of() : arguments.stream().map(a -> a == null ? "" : a).toList();
        successCodes = successCodes == null ? List.of() : successCodes.stream().filter(Objects::nonNull).toList();
        if (successCodes.isEmpty()) {
            successCodes = List.of(0);
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        if (outputExtension != null) {
            outputExtension = outputExtension.strip();
            while (outputExtension.startsWith(".")) {
                outputExtension = outputExtension.substring(1);
            }
        }
    }

    /** The program and the number of arguments only (they may hold secrets). */
    @Override
    public String toString() {
        return "ExecConfig[program=" + program + ", arguments=" + arguments.size() + ", outputExtension="
                + outputExtension + ", successCodes=" + successCodes + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
