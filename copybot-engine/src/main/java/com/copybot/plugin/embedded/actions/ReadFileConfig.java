package com.copybot.plugin.embedded.actions;

public record ReadFileConfig(
        String path,

        boolean recursive
) {
}
