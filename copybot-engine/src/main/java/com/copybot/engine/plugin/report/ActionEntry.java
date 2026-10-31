package com.copybot.engine.plugin.report;

import com.copybot.engine.pipeline.StepType;

/** An action of a loaded plugin; name localized, the code when the plugin has none. */
public record ActionEntry(StepType type, String code, String name) {
}
