package com.copybot.ui.model;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.config.ConfigSchema;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JavaFX-free model of the pipeline editor's output-pattern helper: what to re-run when the editor changes, the keys
 * a sample offers (for the key picker), and the summary of a pattern over a sample (counts, example, rows).
 */
public final class PatternHelperModel {

    private static final int MAX_EXAMPLES = 3;
    /** The ⚠ tooltip of a key names at most this many files lacking it. */
    private static final int MAX_MISSING_NAMES = 10;

    private PatternHelperModel() {
    }

    /** What the helper has to redo before showing a sample consistent with the editor. */
    public enum Rerun { NONE, LIST, ANALYSE }

    /**
     * An available key, with a few example values, how many sampled items carry it, and the source files of
     * those which do not (each named once).
     */
    public record KeyRow(String key, List<String> examples, int present, int total, List<String> missingIn) {
        public KeyRow {
            examples = List.copyOf(examples);
            missingIn = List.copyOf(missingIn);
        }

        public boolean partial() {
            return present < total;
        }
    }

    /** One item resolved by a pattern: its path and the expressions without value, or the error of its analysis. */
    public record Row(String name, String path, List<String> missing, String error) {
        public Row {
            missing = List.copyOf(missing);
        }

        public boolean hasMissing() {
            return !missing.isEmpty();
        }
    }

    /**
     * A pattern over a sample: the syntax error (then nothing else), the number of items it resolves fully and of
     * those with a missing expression, what the step does with the latter, the example row (the first missing one,
     * else the first one) and the rows, missing ones first then the others in sample order.
     */
    public record Summary(Optional<String> syntaxError, int ok, int missing, String effect, Optional<Row> example,
                          List<Row> rows) {
        public Summary {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Decides what to re-run. The strings are the JSON of the input steps and of the analysis plus action steps;
     * a null sampled input means nothing was sampled yet.
     */
    public static Rerun rerun(String sampledIn, String sampledProcessing, String currentIn, String currentProcessing) {
        if (sampledIn == null || !sampledIn.equals(currentIn)) {
            return Rerun.LIST;
        }
        return sampledProcessing != null && sampledProcessing.equals(currentProcessing) ? Rerun.NONE : Rerun.ANALYSE;
    }

    /**
     * The display keys of the items without error: complete ones first, then partial ones, each alphabetically.
     * Without a usable sample (null or failed): the known pattern variables, without examples.
     */
    public static List<KeyRow> keys(Sample sample) {
        if (sample == null || sample.failure().isPresent()) {
            return ConfigSchema.PATTERN_VARIABLES.stream()
                    .map(variable -> new KeyRow(variable, List.of(), 0, 0, List.of())).toList();
        }
        Map<String, LinkedHashSet<String>> examples = new LinkedHashMap<>();
        Map<String, Integer> present = new HashMap<>();
        List<SampleItem> valid = sample.items().stream().filter(item -> item.error().isEmpty()).toList();
        for (SampleItem item : valid) {
            for (Map.Entry<String, String> entry : item.display().entrySet()) {
                LinkedHashSet<String> values = examples.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>());
                if (!entry.getValue().isBlank()) {
                    present.merge(entry.getKey(), 1, Integer::sum);
                    if (values.size() < MAX_EXAMPLES) {
                        values.add(entry.getValue());
                    }
                }
            }
        }
        List<KeyRow> rows = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : examples.entrySet()) {
            String key = entry.getKey();
            LinkedHashSet<String> missingIn = new LinkedHashSet<>();
            for (SampleItem item : valid) {
                String value = item.display().get(key);
                if (value == null || value.isBlank()) {
                    missingIn.add(item.sourceName());
                }
            }
            rows.add(new KeyRow(key, List.copyOf(entry.getValue()), present.getOrDefault(key, 0), valid.size(),
                    List.copyOf(missingIn)));
        }
        rows.sort(Comparator.comparing(KeyRow::partial).thenComparing(KeyRow::key));
        return rows;
    }

    /** The tooltip of the ⚠ of a partial key: the files lacking it, at most ten then the count of the others. */
    public static String missingTooltip(KeyRow row) {
        List<String> names = row.missingIn();
        String shown = String.join(", ", names.subList(0, Math.min(names.size(), MAX_MISSING_NAMES)));
        if (names.size() > MAX_MISSING_NAMES) {
            shown += " " + ResourcesEngine.getString("helper.and-others", names.size() - MAX_MISSING_NAMES);
        }
        return ResourcesEngine.getString("helper.missing-in", shown);
    }

    /** The syntax error of the pattern, empty when it parses; independent of any sample. */
    public static Optional<String> syntaxError(String pattern) {
        try {
            OutPattern.parse(pattern);
            return Optional.empty();
        } catch (CopybotException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** The pattern resolved against every item of the sample, in sample order; empty for an invalid pattern. */
    public static List<Row> rows(String pattern, Sample sample) {
        if (syntaxError(pattern).isPresent()) {
            return List.of();
        }
        OutPattern parsed = OutPattern.parse(pattern);
        List<Row> rows = new ArrayList<>();
        for (SampleItem item : sample.items()) {
            if (item.error().isPresent()) {
                rows.add(new Row(item.name(), null, List.of(), item.error().get()));
                continue;
            }
            OutPattern.Resolution resolution = parsed.resolve(item.display());
            rows.add(new Row(item.name(), resolution.text(), resolution.missing(), null));
        }
        return rows;
    }

    /** The summary of the pattern over the sample, the effect of a missing key according to the step's policy. */
    public static Summary summary(String pattern, String onMissingKey, Sample sample) {
        String effect = ResourcesEngine.getString("helper.effect."
                + ("skip".equals(onMissingKey) || "literal".equals(onMissingKey) ? onMissingKey : "error"));
        Optional<String> error = syntaxError(pattern);
        if (error.isPresent()) {
            return new Summary(error, 0, 0, effect, Optional.empty(), List.of());
        }
        List<Row> rows = rows(pattern, sample);
        List<Row> ordered = new ArrayList<>(rows.stream().filter(Row::hasMissing).toList());
        int missing = ordered.size();
        int ok = (int) rows.stream().filter(row -> row.error() == null && !row.hasMissing()).count();
        rows.stream().filter(row -> !row.hasMissing()).forEach(ordered::add);
        Optional<Row> example = ordered.stream().findFirst();
        return new Summary(Optional.empty(), ok, missing, effect, example, ordered);
    }

    /** The text with <code>{key}</code> inserted at the caret (clamped to the text). */
    public static String insert(String text, int caret, String key) {
        int at = Math.max(0, Math.min(caret, text.length()));
        return text.substring(0, at) + "{" + key + "}" + text.substring(at);
    }

    /** How many source files the sample kept (an item can produce several rows). */
    public static int sampledFiles(Sample sample) {
        return (int) sample.items().stream().map(SampleItem::sourceName).distinct().count();
    }

    /** "Sample: K files out of L", and whether the listing was cut. */
    public static String sampleLine(Sample sample) {
        String text = ResourcesEngine.getString("helper.sample", sampledFiles(sample), sample.listed());
        return sample.truncated() ? text + " " + ResourcesEngine.getString("helper.truncated") : text;
    }
}
