package com.copybot.plugin.notifyemail.actions;

import com.copybot.plugin.api.action.ItemOutcome;
import com.copybot.plugin.api.action.RunItem;
import com.copybot.plugin.api.action.RunReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subject and the two bodies (text and HTML) of the message of a run: outcome, failure, duration, counts,
 * created folders, published values, warnings, then the items as {@code details} says. Each list stops after
 * {@value #MAX_LINES} lines with "... and N others".
 */
final class EmailContent {

    /** A localised text of the plugin, formatted with its arguments. */
    @FunctionalInterface
    interface Texts {
        String get(String key, Object... args);
    }

    static final int MAX_LINES = 200;

    private static final Pattern VARIABLE = Pattern.compile("\\{(pipeline|outcome|written|skipped|errors|duration"
            + "|output:[^{}]+)}");

    private record Section(String title, List<String> lines, int more) {
    }

    private final RunReport report;
    private final String details;
    private final Texts texts;

    EmailContent(RunReport report, String details, Texts texts) {
        this.report = report;
        this.details = details;
        this.texts = texts;
    }

    /** The subject: the variables replaced (an unknown {name} stays, an absent output gives ""), on one line. */
    String fill(String template) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "pipeline" -> report.pipelineName();
                case "outcome" -> outcome();
                case "written" -> String.valueOf(report.counts().written());
                case "skipped" -> String.valueOf(report.counts().skipped());
                case "errors" -> String.valueOf(report.counts().errors());
                case "duration" -> duration();
                default -> report.outputs().getOrDefault(name.substring("output:".length()), "");
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return oneLine(out.toString());
    }

    String text() {
        StringBuilder out = new StringBuilder();
        out.append(title()).append("\n\n");
        for (String[] row : summary()) {
            out.append(texts.get("email.line", row[0], row[1])).append('\n');
        }
        for (Section section : sections()) {
            out.append('\n').append(section.title()).append('\n');
            for (String line : section.lines()) {
                out.append("  ").append(line).append('\n');
            }
            if (section.more() > 0) {
                out.append("  ").append(texts.get("email.more", section.more())).append('\n');
            }
        }
        return out.toString();
    }

    String html() {
        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"></head>")
                .append("<body style=\"font-family: sans-serif; font-size: 14px\">\n");
        out.append("<h2>").append(escape(title())).append("</h2>\n<table>\n");
        for (String[] row : summary()) {
            out.append("<tr><th style=\"text-align: left; padding-right: 1em\">").append(escape(row[0]))
                    .append("</th><td>").append(escape(row[1])).append("</td></tr>\n");
        }
        out.append("</table>\n");
        for (Section section : sections()) {
            out.append("<h3>").append(escape(section.title())).append("</h3>\n<ul>\n");
            for (String line : section.lines()) {
                out.append("<li>").append(escape(line)).append("</li>\n");
            }
            if (section.more() > 0) {
                out.append("<li><i>").append(escape(texts.get("email.more", section.more()))).append("</i></li>\n");
            }
            out.append("</ul>\n");
        }
        return out.append("</body></html>\n").toString();
    }

    // --- parts ---

    private String title() {
        return texts.get("email.title", report.pipelineName());
    }

    private String outcome() {
        return texts.get("email.outcome." + report.outcome().name().toLowerCase(Locale.ROOT));
    }

    private List<String[]> summary() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{texts.get("email.label.outcome"), outcome()});
        if (report.failure() != null && !report.failure().isBlank()) {
            rows.add(new String[]{texts.get("email.label.failure"), oneLine(report.failure())});
        }
        rows.add(new String[]{texts.get("email.label.duration"), duration()});
        rows.add(new String[]{texts.get("email.label.written"), String.valueOf(report.counts().written())});
        rows.add(new String[]{texts.get("email.label.skipped"), String.valueOf(report.counts().skipped())});
        rows.add(new String[]{texts.get("email.label.errors"), String.valueOf(report.counts().errors())});
        rows.add(new String[]{texts.get("email.label.filtered"), String.valueOf(report.counts().filtered())});
        return rows;
    }

    private List<Section> sections() {
        List<Section> sections = new ArrayList<>();
        add(sections, texts.get("email.section.directories"),
                report.createdDirectories().stream().map(Path::toString).toList());
        add(sections, texts.get("email.section.outputs"), report.outputs().entrySet().stream()
                .map(Map.Entry::getKey).sorted()
                .map(key -> texts.get("email.line", key, oneLine(report.outputs().get(key)))).toList());
        add(sections, texts.get("email.section.warnings"), report.warnings().stream().map(EmailContent::oneLine).toList());
        List<RunItem> items = switch (details) {
            case "all" -> report.items();
            case "errors" -> report.items().stream().filter(item -> item.outcome() == ItemOutcome.ERROR).toList();
            default -> List.of();
        };
        add(sections, texts.get(details.equals("all") ? "email.section.all" : "email.section.errors", items.size()),
                items.stream().map(this::line).toList());
        return sections;
    }

    /** A section of at most {@link #MAX_LINES} lines; none when there is nothing to list. */
    private static void add(List<Section> sections, String title, List<String> lines) {
        if (!lines.isEmpty()) {
            int shown = Math.min(lines.size(), MAX_LINES);
            sections.add(new Section(title, lines.subList(0, shown), lines.size() - shown));
        }
    }

    private String line(RunItem item) {
        String name = oneLine(item.name());
        String reason = item.reason() == null || item.reason().isBlank() ? null : oneLine(item.reason());
        return switch (item.outcome()) {
            case WRITTEN -> texts.get("email.item.written", name, item.target() == null ? "" : item.target().toString());
            case SKIPPED -> reason == null ? texts.get("email.item.skipped-plain", name)
                    : texts.get("email.item.skipped", name, reason);
            case ERROR -> reason == null ? texts.get("email.item.error-plain", name)
                    : texts.get("email.item.error", name, reason);
            case FILTERED -> texts.get("email.item.filtered", name);
        };
    }

    private String duration() {
        long seconds = Math.max(0, report.duration().toSeconds());
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return "%dh %02dm %02ds".formatted(hours, minutes, rest);
        }
        return minutes > 0 ? "%dm %02ds".formatted(minutes, rest) : rest + "s";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
