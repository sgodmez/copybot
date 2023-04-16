package com.copybot.plugin.api;

public class CBPluginDeclaration {

    private final String name;
    private final String code;
    private final String version;
    private final String description;
    private final String dependsOn;
    private final String dependsOnOptional;

    public CBPluginDeclaration(String name, String code, String version, String description, String dependsOn, String dependsOnOptional) {
        this.name = name;
        this.code = code;
        this.version = version;
        this.description = description;
        this.dependsOn = dependsOn;
        this.dependsOnOptional = dependsOnOptional;
    }
}
