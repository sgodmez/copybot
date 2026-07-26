package com.copybot.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record CopybotConfig(
        Path pluginPath,

        Path devPluginPaths,

        /**
         * Resource capacities. Exact name ("disk:C:\\") or prefix pattern ("disk:*").
         */
        Map<String, Integer> resources,

        /**
         * Groups of resource names that alias to a single resource
         * (e.g. two partitions of the same physical disk).
         */
        List<List<String>> resourceGroups
) {
}
