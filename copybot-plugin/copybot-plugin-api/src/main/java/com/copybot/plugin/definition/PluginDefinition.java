package com.copybot.plugin.definition;

import java.util.List;

public record PluginDefinition(
        String actionName,
        String version,
        List<String> pluginDependencies,
        // List<String> optionalPluginDependencies, // v2 ?
        String author
) {
}
