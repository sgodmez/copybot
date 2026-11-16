package com.copybot.engine.pipeline;

import com.copybot.plugin.api.action.RunOutcome;
import com.copybot.plugin.embedded.CBEmbeddedPlugin;
import com.copybot.plugin.embedded.actions.PostProcessAction;
import com.google.gson.JsonElement;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An entry of the pipeline's {@code runEnd} list: an end-of-run action and when it runs.
 *
 * @param plugin         the plugin of the action; absent: the embedded one
 * @param action         the action code
 * @param version        optional discriminant if multiple versions of the plugin are loaded
 * @param when           the outcomes it runs for, among "success", "error" and "cancelled"; absent:
 *                       {@link #DEFAULT_WHEN} (an empty list or an unknown value is refused, see
 *                       {@link PipelineChecks#runEndRefusal})
 * @param timeoutSeconds the time the action may take; absent: its default, 0: no limit
 * @param actionConfig   the configuration of the action
 */
public record RunEndStepConfig(
        String plugin,
        String action,
        String version,
        List<String> when,
        Integer timeoutSeconds,
        JsonElement actionConfig
) {

    /** The embedded action that launches a program (formerly the "postProcess" block). */
    public static final String POST_PROCESS = PostProcessAction.CODE;

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
    public static final String CANCELLED = "cancelled";

    /** The conditions of an entry without "when": those of the former post-processing. */
    public static final List<String> DEFAULT_WHEN = List.of(SUCCESS, ERROR);

    /** The step the plugin engine resolves. */
    public PipelineStepConfig asStep() {
        return new PipelineStepConfig(plugin, action, version, null, null, null, null, actionConfig);
    }

    /** The outcomes this entry runs for; the unknown values (refused when the pipeline is read) are left out. */
    public Set<RunOutcome> outcomes() {
        Set<RunOutcome> outcomes = EnumSet.noneOf(RunOutcome.class);
        for (String value : when == null ? DEFAULT_WHEN : when) {
            outcome(value).ifPresent(outcomes::add);
        }
        return outcomes;
    }

    /** Whether this is the embedded post.process action. */
    public boolean isPostProcess() {
        return POST_PROCESS.equals(action) && isEmbedded(plugin);
    }

    static boolean isEmbedded(String plugin) {
        return plugin == null || plugin.isBlank() || plugin.equals(CBEmbeddedPlugin.EMBEDDED_PLUGN_NAME);
    }

    /** The outcome of a value of "when", empty for an unknown one. */
    public static Optional<RunOutcome> outcome(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case SUCCESS -> Optional.of(RunOutcome.SUCCESS);
            case ERROR -> Optional.of(RunOutcome.ERROR);
            case CANCELLED -> Optional.of(RunOutcome.CANCELLED);
            default -> Optional.empty();
        };
    }
}
