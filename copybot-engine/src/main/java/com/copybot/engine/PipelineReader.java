package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.exception.CopybotException;
import com.copybot.utils.GsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a pipeline file into its configuration, refusing the enum members Gson would silently read as null. */
final class PipelineReader {

    private PipelineReader() {
    }

    /** @throws CopybotException pipeline.not-found / pipeline.not-json, or an unknown enum name */
    static PipelineConfig read(Path pipelinePath) {
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
        return pipelineConfig;
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
