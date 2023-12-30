package com.copybot.plugin.embedded.actions;

//TODO : patterns & exclusions
public record FileReadConfig(
        String path,

        boolean recursive
) {
}
