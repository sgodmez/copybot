package com.copybot.ui.model;

import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleItem;
import com.copybot.exception.CopybotException;
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
 * a sample offers, the live preview of a pattern and the status line.
 */
public final class PatternHelperModel {

    private static final int MAX_EXAMPLES = 3;

    private PatternHelperModel() {
    }

    /** What the helper has to redo before showing a sample consistent with the editor. */
    public enum Rerun { NONE, LIST, ANALYSE }

    /** An available key, with a few example values and how many sampled items carry it. */
    public record KeyRow(String key, List<String> examples, int present, int total) {
        public boolean partial() {
            return present < total;
        }
    }

    /** One previewed item: the resolved path and missing keys, or the effect of a missing key, or its error. */
    public record PreviewRow(String name, String path, List<String> missing, String effect, String error) {
    }

    /** The preview of a pattern over a sample: a syntax error, or one row per sampled item. */
    public record Preview(Optional<String> syntaxError, List<PreviewRow> rows) {
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

    /** The display keys of the items without error: complete ones first, then partial ones, each alphabetically. */
    public static List<KeyRow> keys(Sample sample) {
        Map<String, LinkedHashSet<String>> examples = new LinkedHashMap<>();
        Map<String, Integer> present = new HashMap<>();
        int total = 0;
        for (SampleItem item : sample.items()) {
            if (item.error().isPresent()) {
                continue;
            }
            total++;
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
            rows.add(new KeyRow(entry.getKey(), List.copyOf(entry.getValue()),
                    present.getOrDefault(entry.getKey(), 0), total));
        }
        rows.sort(Comparator.comparing(KeyRow::partial).thenComparing(KeyRow::key));
        return rows;
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

    /** Resolves the pattern against every sampled item, saying what a missing key does according to the policy. */
    public static Preview preview(String pattern, String onMissingKey, Sample sample) {
        Optional<String> error = syntaxError(pattern);
        if (error.isPresent()) {
            return new Preview(error, List.of());
        }
        OutPattern parsed = OutPattern.parse(pattern);
        String effectKey = "helper.effect."
                + ("skip".equals(onMissingKey) || "literal".equals(onMissingKey) ? onMissingKey : "error");
        List<PreviewRow> rows = new ArrayList<>();
        for (SampleItem item : sample.items()) {
            if (item.error().isPresent()) {
                rows.add(new PreviewRow(item.name(), null, List.of(), null, item.error().get()));
                continue;
            }
            OutPattern.Resolution resolution = parsed.resolve(item.display());
            rows.add(new PreviewRow(item.name(), resolution.text(), resolution.missing(),
                    resolution.complete() ? null : ResourcesEngine.getString(effectKey), null));
        }
        return new Preview(Optional.empty(), rows);
    }

    /** The text with <code>{key}</code> inserted at the caret (clamped to the text). */
    public static String insert(String text, int caret, String key) {
        int at = Math.max(0, Math.min(caret, text.length()));
        return text.substring(0, at) + "{" + key + "}" + text.substring(at);
    }

    /** The status line: the failure message, or the number of sampled files out of those listed. */
    public static String status(Sample sample) {
        if (sample.failure().isPresent()) {
            return sample.failure().get();
        }
        int kept = (int) sample.items().stream().map(SampleItem::sourceName).distinct().count();
        String text = ResourcesEngine.getString("helper.sample", kept, sample.listed());
        return sample.truncated() ? text + " " + ResourcesEngine.getString("helper.truncated") : text;
    }
}
