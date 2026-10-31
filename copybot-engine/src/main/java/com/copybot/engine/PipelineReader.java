package com.copybot.engine;

import com.copybot.engine.pipeline.MovedActionsMigration;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.RunEndMigration;
import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a pipeline file into its configuration, refusing the enum members Gson would silently read as null; a former
 * "postProcess" block is read as the first run-end entry, and invalid run-end conditions are refused.
 */
public final class PipelineReader {

    private PipelineReader() {
    }

    /** @throws CopybotException pipeline.not-found / pipeline.not-json, or an unknown enum name */
    public static PipelineConfig read(Path pipelinePath) {
        if (!Files.isReadable(pipelinePath)) {
            throw CopybotException.ofResource("pipeline.not-found", pipelinePath);
        }
        JsonElement tree;
        try (var reader = Files.newBufferedReader(pipelinePath)) {
            tree = JsonParser.parseReader(reader);
        } catch (IOException | JsonParseException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
        // before Gson: its enum adapter may fail on a non-string mode, which would read as "not a JSON"
        JsonElement resume = tree.isJsonObject() ? tree.getAsJsonObject().get("resume") : null;
        checkEnumType(resume, "mode", "resume.mode.unknown");
        checkEnumType(tree, "execution", "execution.unknown");
        checkEnumType(tree, "conflictCheck", "conflict-check.unknown");
        checkEnumType(resume, "destinationCheck", "resume.destination-check.unknown");
        checkEnumType(resume, "destinationMatch", "resume.destination-match.unknown");
        checkSecondaryOnError(tree);
        if (tree.isJsonObject()) {
            RunEndMigration.migrate(tree.getAsJsonObject());
            MovedActionsMigration.migrate(tree.getAsJsonObject());
        }
        PipelineConfig pipelineConfig;
        try {
            pipelineConfig = GsonUtil.getGson().fromJson(tree, PipelineConfig.class);
        } catch (JsonParseException e) {
            throw CopybotException.ofResource(e, "pipeline.not-json", pipelinePath);
        }
        if (pipelineConfig == null) { // empty file
            throw CopybotException.ofResource("pipeline.not-json", pipelinePath);
        }
        checkEnumName(resume, "mode", pipelineConfig.resume() == null ? null : pipelineConfig.resume().mode(),
                "resume.mode.unknown");
        checkEnumName(tree, "execution", pipelineConfig.execution(), "execution.unknown");
        checkEnumName(tree, "conflictCheck", pipelineConfig.conflictCheck(), "conflict-check.unknown");
        checkEnumName(resume, "destinationCheck",
                pipelineConfig.resume() == null ? null : pipelineConfig.resume().destinationCheck(),
                "resume.destination-check.unknown");
        checkEnumName(resume, "destinationMatch",
                pipelineConfig.resume() == null ? null : pipelineConfig.resume().destinationMatch(),
                "resume.destination-match.unknown");
        PipelineChecks.runEndRefusal(pipelineConfig).ifPresent(message -> {
            throw CopybotException.of(message);
        });
        PipelineChecks.secondaryOutputsRefusal(pipelineConfig).ifPresent(message -> {
            throw CopybotException.of(message);
        });
        return pipelineConfig;
    }

    /**
     * The "onError" of a secondary output that is neither "fail" nor "warn": Gson would read it as null, which means
     * "fail", silently. Before Gson, like the other enum members.
     */
    private static void checkSecondaryOnError(JsonElement tree) {
        if (!tree.isJsonObject() || !(tree.getAsJsonObject().get("secondaryOutputs") instanceof JsonArray array)) {
            return;
        }
        int index = 0;
        for (JsonElement branch : array) {
            index++;
            if (!branch.isJsonObject()) {
                continue;
            }
            JsonElement value = branch.getAsJsonObject().get("onError");
            if (value == null || value.isJsonNull()) {
                continue;
            }
            boolean known = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && ("fail".equals(value.getAsString()) || "warn".equals(value.getAsString()));
            if (!known) {
                JsonElement name = branch.getAsJsonObject().get("name");
                String label = name != null && name.isJsonPrimitive() && !name.getAsString().isBlank()
                        ? "\"" + name.getAsString() + "\"" : "#" + index;
                throw CopybotException.ofResource("secondary.on-error.unknown", label,
                        value.isJsonPrimitive() ? value.getAsString() : value.toString());
            }
        }
    }

    /** Before Gson, like the resume mode: a present, non-null member that is not a string is an unknown name. */
    private static void checkEnumType(JsonElement object, String member, String resourceKey) {
        if (object == null || !object.isJsonObject()) {
            return;
        }
        JsonElement value = object.getAsJsonObject().get(member);
        if (value == null || value.isJsonNull() || value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return;
        }
        throw CopybotException.ofResource(resourceKey, value.isJsonPrimitive() ? value.getAsString() : value.toString());
    }

    /**
     * A present, non-null member that Gson read as null (an unknown name, or not a string: spec execution-mode §1)
     * would silently mean the default.
     */
    private static void checkEnumName(JsonElement object, String member, Object read, String resourceKey) {
        if (read != null || object == null || !object.isJsonObject()) {
            return;
        }
        JsonElement value = object.getAsJsonObject().get(member);
        if (value != null && !value.isJsonNull()) {
            throw CopybotException.ofResource(resourceKey,
                    value.isJsonPrimitive() ? value.getAsString() : value.toString());
        }
    }
}
