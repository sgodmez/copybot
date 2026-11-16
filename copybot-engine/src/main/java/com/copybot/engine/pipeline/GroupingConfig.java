package com.copybot.engine.pipeline;

import java.util.List;

/** The optional {@code grouping} block of the pipeline: the rules that attach companion files to a main file. */
public record GroupingConfig(List<GroupingRuleConfig> rules) {
}
