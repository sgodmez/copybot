package com.copybot.exception;

import com.copybot.resources.ResourcesEngine;

public class PluginNotFoundException extends CopybotException {

    public PluginNotFoundException(String name) {
        super(ResourcesEngine.getString("plugin.not-found",name));
    }

}