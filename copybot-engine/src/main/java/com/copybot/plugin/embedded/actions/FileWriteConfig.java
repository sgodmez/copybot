package com.copybot.plugin.embedded.actions;

public record FileWriteConfig(
        String outPattern,

        boolean overwrite
) {
}
