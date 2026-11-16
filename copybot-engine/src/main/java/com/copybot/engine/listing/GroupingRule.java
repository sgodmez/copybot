package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.plugin.api.action.CompanionLink;
import com.copybot.plugin.api.action.CompanionRole;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A grouping rule compiled once: lower-cased extensions, compiled expressions. The rules are valid (see the validation). */
final class GroupingRule {

    enum Type { SAME_BASE, DOUBLE_EXTENSION, FOLDER, REGEX }

    /** The role of a companion: written, read only, or dropped. */
    enum Role {
        COPY(CompanionRole.COPY), READ(CompanionRole.READ), IGNORE(null);

        final CompanionRole companionRole;

        Role(CompanionRole companionRole) {
            this.companionRole = companionRole;
        }

        static Role of(String name) {
            return name == null ? COPY : Role.valueOf(name.toUpperCase(Locale.ROOT));
        }
    }

    private static final String BASE_MARK = "{base}";

    final Type type;
    /** The main extensions, lower case, in priority order. */
    final List<String> mainExtensions;
    /** The companion extensions (lower case) of a sameBase or doubleExtension rule, with their role. */
    final Map<String, Role> companionRoles = new HashMap<>();
    /** The regex main expression, whole-name match. */
    final Pattern mainRegex;
    /** The companion expression with {@code \1}..{@code \9} still to replace, regex rule. */
    final String companionTemplate;
    /** The role of a folder or regex rule. */
    final Role role;
    final CompanionLink link;
    /** The name of a {@code folder} rule folder, with a capture group where {@code {base}} stands. */
    final Pattern folderName;
    /** The folder name without {@code {base}}: the suffix start for the files of the folder. */
    final String folderSuffix;

    GroupingRule(GroupingRuleConfig config) {
        type = switch (config.type()) {
            case "sameBase" -> Type.SAME_BASE;
            case "doubleExtension" -> Type.DOUBLE_EXTENSION;
            case "folder" -> Type.FOLDER;
            default -> Type.REGEX;
        };
        mainExtensions = config.main() == null ? List.of()
                : config.main().stream().map(e -> e.toLowerCase(Locale.ROOT)).toList();
        if (config.companions() != null) {
            config.companions().forEach((ext, role) -> companionRoles.put(ext.toLowerCase(Locale.ROOT), Role.of(role)));
        }
        role = Role.of(config.role());
        mainRegex = type == Type.REGEX
                ? Pattern.compile(config.main().get(0), Pattern.CASE_INSENSITIVE) : null;
        companionTemplate = config.companion();
        link = "base".equalsIgnoreCase(config.link()) || type == Type.FOLDER ? CompanionLink.BASE : CompanionLink.NAME;
        if (type == Type.FOLDER && config.folder() != null && config.folder().contains(BASE_MARK)) {
            String template = config.folder();
            int at = template.indexOf(BASE_MARK);
            folderName = Pattern.compile(Pattern.quote(template.substring(0, at)) + "(.+)"
                    + Pattern.quote(template.substring(at + BASE_MARK.length())), Pattern.CASE_INSENSITIVE);
            folderSuffix = template.replace(BASE_MARK, "");
        } else {
            folderName = null;
            folderSuffix = null;
        }
    }

    /** The {@code <base>} a folder name stands for under this rule, null: the name does not match (or no folder rule). */
    String folderBase(String folderNameText) {
        if (folderName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = folderName.matcher(folderNameText);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The companion expression of this regex rule for a main whose groups were captured. */
    Pattern companionPattern(java.util.regex.Matcher main) {
        return Pattern.compile(GroupingRuleConfig.companionRegex(companionTemplate,
                group -> group <= main.groupCount() ? main.group(group) : null), Pattern.CASE_INSENSITIVE);
    }
}
