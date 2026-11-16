package com.copybot.plugin.api.grouping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One grouping rule of a {@link GroupingPreset}: the same meaning and the same checks as a rule written in a pipeline
 * (spec grouping/archives). The fields a type does not use are null; the factories fill the ones it does.
 *
 * @param type       the kind of rule, required
 * @param main       sameBase and folder: the main extensions, in priority order; regex: the one main expression;
 *                   doubleExtension: unused (every file named after another one's full name is a candidate)
 * @param companions sameBase and doubleExtension: the companion extensions (neither null nor blank) with their role
 * @param folder     folder: the name of the companion folder, starting with {@code {base}} (e.g. {@code {base}_files})
 * @param role       folder and regex: the role of the companions (null: copy)
 * @param companion  regex: the companion expression, {@code \1}..{@code \9} standing for the groups of the main one
 * @param link       regex: how the companion target name derives from the main one (null: name)
 */
public record GroupingRule(
        GroupingRuleType type,
        List<String> main,
        Map<String, GroupingRole> companions,
        String folder,
        GroupingRole role,
        String companion,
        GroupingLink link) {

    /**
     * @throws NullPointerException     no type, or a null main entry
     * @throws IllegalArgumentException a null or blank companion extension
     */
    public GroupingRule {
        Objects.requireNonNull(type, "type");
        main = main == null ? null : List.copyOf(main);
        if (companions != null) {
            for (String extension : companions.keySet()) {
                if (extension == null || extension.isBlank()) {
                    throw new IllegalArgumentException("blank companion extension in a " + type + " grouping rule");
                }
            }
            companions = Collections.unmodifiableMap(new LinkedHashMap<>(companions));
        }
    }

    /** The files of the same base name as a main file of these extensions (in priority order) are its companions. */
    public static GroupingRule sameBase(List<String> main, Map<String, GroupingRole> companions) {
        return new GroupingRule(GroupingRuleType.SAME_BASE, main, companions, null, null, null, null);
    }

    /** The files named after a file's full name, then one of these extensions, are its companions. */
    public static GroupingRule doubleExtension(Map<String, GroupingRole> companions) {
        return new GroupingRule(GroupingRuleType.DOUBLE_EXTENSION, null, companions, null, null, null, null);
    }

    /** The folder named after a main file of these extensions ({@code folder} starts with {@code {base}}). */
    public static GroupingRule folder(List<String> main, String folder, GroupingRole role) {
        return new GroupingRule(GroupingRuleType.FOLDER, main, null, folder, role, null, null);
    }

    /** The files matching the companion expression of a file matching the main one (whole names, case ignored). */
    public static GroupingRule regex(String main, String companion, GroupingRole role, GroupingLink link) {
        return new GroupingRule(GroupingRuleType.REGEX, main == null ? null : List.of(main), null, null, role,
                companion, link);
    }
}
