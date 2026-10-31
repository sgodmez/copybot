package com.copybot.ui.model;

import com.copybot.engine.Projection;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.pattern.OutPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * JavaFX-free model of the "Out pattern" dialog of the plan view: the prefill, the live preview over the chosen
 * files and whether OK is enabled (spec own-out-pattern §3).
 */
public final class OutPatternModel {

    /** The preview shows the target of this many files at most. */
    static final int MAX_ROWS = 3;

    private OutPatternModel() {
    }

    /**
     * What the pattern gives: its syntax error (then no row), else the target of the first files, missing keys listed,
     * how many of all the chosen files lack a key and what the out step does with them (its onMissingKey).
     */
    public record Preview(Optional<String> syntaxError, List<PatternHelperModel.Row> rows, int missing, String effect) {
        public Preview {
            rows = List.copyOf(rows);
        }
    }

    /**
     * The items the dialog works on: for each file, the first item its dry run projected (what the out step writes,
     * after a process step that may rename it, as in the Target column), else the file itself.
     */
    public static List<WorkItem> written(List<WorkItemExecution> files) {
        return files.stream().map(file -> file.getProjection() instanceof Projection.Projected projected
                && !projected.items().isEmpty() ? projected.items().getFirst() : file.getWorkItem()).toList();
    }

    /** The own pattern shared by all these files, else the default one. */
    public static String initialPattern(List<WorkItem> items, String defaultPattern) {
        Optional<String> shared = items.isEmpty() ? Optional.empty() : items.getFirst().getOutPattern();
        for (WorkItem item : items) {
            if (!item.getOutPattern().equals(shared)) {
                return defaultPattern;
            }
        }
        return shared.orElse(defaultPattern);
    }

    /**
     * The pattern over the first files of the selection, the files lacking a key counted over all of them.
     * {@code onMissingKey} is the out step's policy: such a file stays listed with its missing expressions, the
     * consequence worded as in the editor's helper (the step decides at the copy).
     */
    public static Preview preview(String pattern, List<WorkItem> items, String onMissingKey) {
        String effect = PatternHelperModel.effect(onMissingKey);
        Optional<String> error = PatternHelperModel.syntaxError(pattern);
        if (error.isPresent()) {
            return new Preview(error, List.of(), 0, effect);
        }
        OutPattern parsed = OutPattern.parse(pattern);
        List<PatternHelperModel.Row> rows = new ArrayList<>();
        int missing = 0;
        for (WorkItem item : items) {
            OutPattern.Resolution resolution = parsed.resolve(item.getMetadatas().display());
            if (!resolution.missing().isEmpty()) {
                missing++;
            }
            if (rows.size() < MAX_ROWS) {
                rows.add(new PatternHelperModel.Row(item.getNameDisplay(), resolution.text(), resolution.missing(), null));
            }
        }
        return new Preview(Optional.empty(), rows, missing, effect);
    }

    /** OK is enabled unless the pattern does not parse. */
    public static boolean canApply(Preview preview) {
        return preview.syntaxError().isEmpty();
    }

    /** The keys of the chosen files, for the key picker: the union of their display keys, sorted. */
    public static List<String> keys(List<WorkItem> items) {
        Set<String> keys = new TreeSet<>();
        items.forEach(item -> keys.addAll(item.getMetadatas().display().keySet()));
        return List.copyOf(keys);
    }
}
