package com.copybot.engine.pipeline;

import com.google.gson.JsonElement;

public record PipelineStep(
        String action,

        /**
         * optional discriminant if multiple version of the plugin is loaded
         */
        String version,
        JsonElement actionConfig
) {
}
