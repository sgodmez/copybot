package com.copybot.engine.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Rewrites the steps of actions that moved to another plugin, on the JSON tree: the engine does it when it reads a
 * pipeline, the editor when it opens one (so that saving writes the new form), like {@link RunEndMigration}. The
 * ffmpeg actions were in the plugin exec until 0.8.0-beta.1 (exec 1.0.0); they are now in the plugin ffmpeg.
 * <p>
 * A moved step names the new plugin and loses its "version", which was the one of the former plugin. The in,
 * analyse, process and out steps are concerned, and those of the secondary outputs; not the run-end list (no such
 * action ran at the end of a run).
 */
public final class MovedActionsMigration {

    /** An action whose code starts with {@code actionPrefix}, once in {@code from}, now in {@code to} (modules). */
    record Move(String from, String actionPrefix, String to) {
    }

    static final List<Move> MOVES = List.of(
            new Move("com.copybot.plugin.exec", "ffmpeg.", "com.copybot.plugin.ffmpeg"));

    private static final List<String> STEP_LISTS = List.of("inSteps", "analyseSteps", "actionSteps");
    private static final String OUT_STEP = "outStep";
    private static final String SECONDARY_OUTPUTS = "secondaryOutputs";
    private static final String BRANCH_STEP_LIST = "actionSteps";

    private MovedActionsMigration() {
    }

    /**
     * Rewrites the moved steps of the pipeline; members of an unexpected shape are left as they are (the reading
     * refuses them or ignores them as before).
     *
     * @return whether the tree changed
     */
    public static boolean migrate(JsonObject pipeline) {
        boolean changed = false;
        for (String list : STEP_LISTS) {
            changed |= migrateList(pipeline.get(list));
        }
        changed |= migrateStep(pipeline.get(OUT_STEP));
        JsonElement branches = pipeline.get(SECONDARY_OUTPUTS);
        if (branches != null && branches.isJsonArray()) {
            for (JsonElement branch : branches.getAsJsonArray()) {
                if (branch.isJsonObject()) {
                    changed |= migrateList(branch.getAsJsonObject().get(BRANCH_STEP_LIST));
                    changed |= migrateStep(branch.getAsJsonObject().get(OUT_STEP));
                }
            }
        }
        return changed;
    }

    private static boolean migrateList(JsonElement steps) {
        boolean changed = false;
        if (steps != null && steps.isJsonArray()) {
            for (JsonElement step : steps.getAsJsonArray()) {
                changed |= migrateStep(step);
            }
        }
        return changed;
    }

    private static boolean migrateStep(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonObject step = element.getAsJsonObject();
        String plugin = string(step.get("plugin"));
        String action = string(step.get("action"));
        if (plugin == null || action == null) {
            return false;
        }
        for (Move move : MOVES) {
            if (move.from().equals(plugin) && action.startsWith(move.actionPrefix())) {
                step.addProperty("plugin", move.to()); // keeps its place among the members
                step.remove("version");
                return true;
            }
        }
        return false;
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }
}
