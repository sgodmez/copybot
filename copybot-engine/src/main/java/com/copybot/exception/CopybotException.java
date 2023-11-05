package com.copybot.exception;

import com.copybot.resources.ResourcesEngine;

public class CopybotException extends RuntimeException {

    private CopybotException(String message) {
        super(message);
    }

    private CopybotException(String message, Throwable cause) {
        super(message, cause);
    }

    public static CopybotException of(String message) {
        return new CopybotException(message);
    }

    public static CopybotException of(Throwable cause, String message) {
        return new CopybotException(message, cause);
    }

    public static CopybotException ofResource(String resourceBundleKey, Object... args) {
        return new CopybotException(ResourcesEngine.getString(resourceBundleKey, args));
    }

    public static CopybotException ofResource(Throwable cause, String resourceBundleKey, Object... args) {
        return new CopybotException(ResourcesEngine.getString(resourceBundleKey, args), cause);
    }

    public static CopybotException wrapIfNeeded(Throwable e) {
        if (e instanceof CopybotException) return (CopybotException) e;
        return ofResource(e, "error.unknown");
    }

}