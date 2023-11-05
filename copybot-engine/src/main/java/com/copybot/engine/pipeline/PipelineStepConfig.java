package com.copybot.engine.pipeline;

import com.google.gson.JsonElement;

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

        JsonElement actionConfig
) {

    public String getDisplayName() {
        return action + " => " + plugin + (version == null ? "" : ':' + version);
    }
}
