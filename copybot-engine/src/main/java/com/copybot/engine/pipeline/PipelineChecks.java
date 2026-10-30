package com.copybot.engine.pipeline;

import com.copybot.engine.listing.GroupingPresets;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.resources.ResourcesEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a pipeline needs to be prepared (an input step: nothing to list otherwise) and to be executed
 * (an input step, and an output or a process step: analyse steps never do anything with the files).
 * A pipeline without output step but with a process step is valid: the process steps act themselves.
 * Messages are localized.
 */
public final class PipelineChecks {

    private PipelineChecks() {
    }

    /** The reason when the pipeline has no input step (absent or empty), empty otherwise. */
    public static Optional<String> missingInput(PipelineConfig config) {
        return isEmpty(config.inSteps())
                ? Optional.of(ResourcesEngine.getString("pipeline.no-input"))
                : Optional.empty();
    }

    /** The reason when the pipeline has neither output step nor process step, empty otherwise. */
    public static Optional<String> doesNothing(PipelineConfig config) {
        return config.outStep() == null && isEmpty(config.actionSteps())
                ? Optional.of(ResourcesEngine.getString("pipeline.does-nothing"))
                : Optional.empty();
    }

    /** The first reason why the pipeline cannot be executed (no input, then does nothing), empty when it can. */
    public static Optional<String> executionRefusal(PipelineConfig config) {
        return missingInput(config).or(() -> doesNothing(config));
    }

    /** @throws CopybotException pipeline.no-input when the pipeline has no input step */
    public static void requirePreparable(PipelineConfig config) {
        missingInput(config).ifPresent(message -> {
            throw CopybotException.of(message);
        });
    }

    /** @throws CopybotException pipeline.no-input or pipeline.does-nothing */
    public static void requireExecutable(PipelineConfig config) {
        executionRefusal(config).ifPresent(message -> {
            throw CopybotException.of(message);
        });
    }

    /**
     * The reason why the run-end list cannot be used, empty when it can: an entry whose "when" is empty or holds an
     * unknown value (an absent "when" is the default), or whose time limit is negative.
     */
    public static Optional<String> runEndRefusal(PipelineConfig config) {
        int index = 0;
        for (RunEndStepConfig entry : config.runEnd()) {
            index++;
            if (entry.timeoutSeconds() != null && entry.timeoutSeconds() < 0) {
                return Optional.of(ResourcesEngine.getString("run-end.timeout.negative", index, entry.action(),
                        String.valueOf(entry.timeoutSeconds())));
            }
            if (entry.when() == null) {
                continue;
            }
            if (entry.when().isEmpty()) {
                return Optional.of(ResourcesEngine.getString("run-end.when.empty", index, entry.action()));
            }
            for (String value : entry.when()) {
                if (RunEndStepConfig.outcome(value).isEmpty()) {
                    return Optional.of(ResourcesEngine.getString("run-end.when.unknown", index, entry.action(),
                            value));
                }
            }
        }
        return Optional.empty();
    }

    private static final Set<String> RULE_TYPES = Set.of("sameBase", "doubleExtension", "folder", "regex");
    private static final Set<String> ROLES = Set.of("copy", "read", "ignore");
    private static final Set<String> LINKS = Set.of("base", "name");

    /**
     * The reason why the grouping rules cannot be used, empty when they can (or when there are none): an invalid rule
     * (unknown preset, type, role or link, a regex that does not compile, a folder that does not start with
     * {base}), or COPY companions with an output that does not write them. No output action: no refusal.
     * The COPY companions are looked for after the presets are expanded.
     */
    public static Optional<String> groupingRefusal(PipelineConfig config, IOutAction out) {
        GroupingConfig grouping = config.grouping();
        if (grouping == null || grouping.rules() == null) {
            return Optional.empty();
        }
        int index = 0;
        for (GroupingRuleConfig rule : grouping.rules()) {
            index++;
            Optional<String> invalid = invalidRule(rule, index);
            if (invalid.isPresent()) {
                return invalid;
            }
        }
        if (out == null || out.writesCompanions()) {
            return Optional.empty();
        }
        return GroupingPresets.expand(grouping).stream().anyMatch(PipelineChecks::copiesCompanions)
                ? Optional.of(ResourcesEngine.getString("grouping.companions-unsupported", outName(config, out)))
                : Optional.empty();
    }

    private static Optional<String> invalidRule(GroupingRuleConfig rule, int index) {
        if (rule == null) {
            return Optional.of(ResourcesEngine.getString("grouping.rule.type-unknown", index, null));
        }
        if (rule.preset() != null) {
            return GroupingPresets.isKnown(rule.preset()) ? Optional.empty()
                    : Optional.of(ResourcesEngine.getString("grouping.preset.unknown", index, rule.preset()));
        }
        if (rule.type() == null || !RULE_TYPES.contains(rule.type())) {
            return Optional.of(ResourcesEngine.getString("grouping.rule.type-unknown", index, rule.type()));
        }
        if (isUnknown(ROLES, rule.role())) {
            return Optional.of(ResourcesEngine.getString("grouping.rule.value-unknown", index, "role", rule.role()));
        }
        if (isUnknown(LINKS, rule.link())) {
            return Optional.of(ResourcesEngine.getString("grouping.rule.value-unknown", index, "link", rule.link()));
        }
        if (rule.companions() != null) {
            for (String role : rule.companions().values()) {
                if (role == null || isUnknown(ROLES, role)) {
                    return Optional.of(ResourcesEngine.getString("grouping.rule.value-unknown", index, "companions",
                            role));
                }
            }
        }
        if ("folder".equals(rule.type())
                && (rule.folder() == null || !rule.folder().startsWith("{base}"))) {
            return Optional.of(ResourcesEngine.getString("grouping.rule.folder-invalid", index, rule.folder(),
                    "{base}"));
        }
        if ("regex".equals(rule.type())) {
            String main = rule.main() == null || rule.main().size() != 1 ? null : rule.main().get(0);
            int groups;
            try {
                groups = Pattern.compile(main).matcher("").groupCount();
            } catch (PatternSyntaxException | NullPointerException e) {
                return Optional.of(ResourcesEngine.getString("grouping.rule.regex-invalid", index, main));
            }
            if (!isValidCompanion(rule.companion(), groups)) {
                return Optional.of(ResourcesEngine.getString("grouping.rule.regex-invalid", index, rule.companion()));
            }
        }
        return Optional.empty();
    }

    /**
     * A companion expression is given, refers only to groups of the main expression, and compiles once its
     * references are replaced (as the listing does, with an empty text here).
     */
    private static boolean isValidCompanion(String template, int groups) {
        if (template == null || template.isEmpty()) {
            return false;
        }
        boolean[] missingGroup = {false};
        String regex = GroupingRuleConfig.companionRegex(template, group -> {
            missingGroup[0] |= group > groups;
            return "";
        });
        if (missingGroup[0]) {
            return false;
        }
        try {
            Pattern.compile(regex);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** A given value must be one of the allowed ones (an absent value is the default). */
    private static boolean isUnknown(Set<String> allowed, String value) {
        return value != null && !allowed.contains(value);
    }

    /** Whether the (expanded) rule writes its companions next to the main file. */
    private static boolean copiesCompanions(GroupingRuleConfig rule) {
        return switch (rule.type()) {
            case "sameBase", "doubleExtension" -> rule.companions() != null
                    && rule.companions().containsValue("copy");
            default -> rule.role() == null || "copy".equals(rule.role());
        };
    }

    private static String outName(PipelineConfig config, IOutAction out) {
        return config.outStep() == null ? out.getClass().getSimpleName() : config.outStep().getDisplayName();
    }

    private static boolean isEmpty(List<?> steps) {
        return steps == null || steps.isEmpty();
    }
}
