package com.copybot.engine.pipeline;

import com.google.gson.JsonElement;

import java.util.List;

public record PipelineStepConfig(
        String plugin,
        String action,

        /**
         * optional discriminant if multiple version of the plugin is loaded
         */
        String version,

        String filterCondition,

        Integer maxConcurrency,
        Integer priority,

        /**
         * optional additional resource names this step consumes (e.g. ["gpu"])
         */
        List<String> resources,

        JsonElement actionConfig
) {

    public String getDisplayName() {
        return action + " => " + plugin + (version == null ? "" : ':' + version);
    }
}
