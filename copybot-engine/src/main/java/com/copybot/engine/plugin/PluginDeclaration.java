package com.copybot.engine.plugin;

public record PluginDeclaration(
        String name,
        String code,
        String version,
        String description,
        String dependsOn,
        String dependsOnOptional) {
}
