package com.copybot.config;

import java.nio.file.Path;

public record CopybotConfig(
        Path pluginPath,

        Path devPluginPaths
) {
}
