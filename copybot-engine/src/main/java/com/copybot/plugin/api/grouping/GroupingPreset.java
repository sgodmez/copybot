package com.copybot.plugin.api.grouping;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A named set of grouping rules a plugin brings ({@link com.copybot.plugin.api.definition.IPlugin#getGroupingPresets}).
 * A pipeline cites it as {@code {"preset": "<code>", "plugin": "<plugin module name>"}}. Its texts are
 * {@code plugin.<plugin code>.preset.<code>.name} and {@code .description} in the plugin bundle.
 *
 * @param code  lower-case letters, digits and dashes
 * @param rules at least one rule
 */
public record GroupingPreset(String code, List<GroupingRule> rules) {

    private static final Pattern CODE = Pattern.compile("[a-z0-9-]+");

    public GroupingPreset {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid grouping preset code: " + code);
        }
        Objects.requireNonNull(rules, "rules");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("grouping preset without rules: " + code);
        }
        rules = List.copyOf(rules);
    }

    public GroupingPreset(String code, GroupingRule... rules) {
        this(code, List.of(rules));
    }
}
