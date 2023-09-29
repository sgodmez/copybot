package com.copybot.exception;

import com.copybot.resources.ResourcesEngine;

public class CopybotException extends RuntimeException {

    public CopybotException(String message) {
        super(message);
    }

    public static CopybotException ofResourceBundle(String bundleKey, Object... args) {
        return new CopybotException(ResourcesEngine.getResourceBundle().getString(bundleKey, args));
    }

}