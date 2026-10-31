package com.copybot.config;

import java.util.List;
import java.util.Map;

/**
 * The configuration file. An older file that still names {@code pluginPath} or {@code devPluginPaths} is read all
 * the same: the plugins now come from the app folder.
 */
public record CopybotConfig(
        /**
         * Resource capacities. Exact name ("disk:sda", or a folder of the disk: "disk:C:\\") or prefix
         * pattern ("disk:*").
         */
        Map<String, Integer> resources,

        /**
         * Groups of resource names that alias to a single resource
         * (e.g. volumes spread over several disks, which are not joined to a physical disk).
         */
        List<List<String>> resourceGroups
) {
}
