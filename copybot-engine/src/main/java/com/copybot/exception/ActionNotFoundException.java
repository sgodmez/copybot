package com.copybot.exception;

import com.copybot.resources.ResourcesEngine;

public class ActionNotFoundException extends CopybotException {

    public ActionNotFoundException(String name) {
        super(ResourcesEngine.getString("action.not-found", name));
    }

}