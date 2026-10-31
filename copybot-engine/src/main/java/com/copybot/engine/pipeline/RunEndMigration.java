package com.copybot.engine.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Turns the former "postProcess" block of a pipeline into the first entry of its "runEnd" list, on the JSON tree:
 * the engine does it when it reads a pipeline, the editor when it opens one (so that saving writes the new form).
 */
public final class RunEndMigration {

    public static final String POST_PROCESS_BLOCK = "postProcess";
    public static final String RUN_END = "runEnd";

    private RunEndMigration() {
    }

    /**
     * Removes the "postProcess" block of the pipeline; when it has a command, puts a post.process entry with the
     * same command and arguments, run on success and error, first in "runEnd", unless the list already has a
     * post.process entry. A "runEnd" that is not a list is left as it is (the pipeline is then refused).
     *
     * @return whether the tree changed
     */
    public static boolean migrate(JsonObject pipeline) {
        JsonElement block = pipeline.remove(POST_PROCESS_BLOCK);
        if (block == null) {
            return false;
        }
        if (!block.isJsonObject() || !hasCommand(block.getAsJsonObject())) {
            return true;
        }
        JsonElement runEnd = pipeline.get(RUN_END);
        if (runEnd == null || runEnd.isJsonNull()) {
            runEnd = new JsonArray();
            pipeline.add(RUN_END, runEnd);
        } else if (!runEnd.isJsonArray()) {
            return true;
        }
        JsonArray entries = runEnd.getAsJsonArray();
        if (hasPostProcess(entries)) {
            return true;
        }
        JsonObject actionConfig = new JsonObject();
        actionConfig.add("command", block.getAsJsonObject().get("command").deepCopy());
        JsonElement args = block.getAsJsonObject().get("args");
        if (args != null) {
            actionConfig.add("args", args.deepCopy());
        }
        JsonArray when = new JsonArray();
        RunEndStepConfig.DEFAULT_WHEN.forEach(when::add);
        JsonObject entry = new JsonObject();
        entry.addProperty("action", RunEndStepConfig.POST_PROCESS);
        entry.add("when", when);
        entry.add("actionConfig", actionConfig);
        entries.asList().add(0, entry); // a view of the array
        return true;
    }

    private static boolean hasCommand(JsonObject block) {
        JsonElement command = block.get("command");
        return command != null && command.isJsonPrimitive()
                && !PostProcessConfig.unquote(command.getAsString()).isEmpty();
    }

    private static boolean hasPostProcess(JsonArray entries) {
        for (JsonElement entry : entries) {
            if (entry.isJsonObject()) {
                JsonElement action = entry.getAsJsonObject().get("action");
                JsonElement plugin = entry.getAsJsonObject().get("plugin");
                if (action != null && action.isJsonPrimitive()
                        && RunEndStepConfig.POST_PROCESS.equals(action.getAsString())
                        && RunEndStepConfig.isEmbedded(plugin == null || !plugin.isJsonPrimitive()
                        ? null : plugin.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }
}
