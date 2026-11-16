package com.copybot.exception;

import com.copybot.resources.ResourcesEngine;

public class CopybotException extends RuntimeException {

    protected CopybotException(String message) {
        super(message);
    }

    protected CopybotException(Throwable cause, String message) {
        super(message, cause);
    }

    public static CopybotException of(String message) {
        return new CopybotException(message);
    }

    public static CopybotException of(Throwable cause, String message) {
        return new CopybotException(cause, message);
    }

    public static CopybotException ofResource(String resourceBundleKey, Object... args) {
        return new CopybotException(ResourcesEngine.getString(resourceBundleKey, args));
    }

    public static CopybotException ofResource(Throwable cause, String resourceBundleKey, Object... args) {
        return new CopybotException(cause, ResourcesEngine.getString(resourceBundleKey, args));
    }

    public static CopybotException wrapIfNeeded(Throwable e) {
        if (e instanceof CopybotException) return (CopybotException) e;
        return ofResource(e, "error.unknown");
    }

    public static CopybotException wrapActionError(Throwable cause, String actionLabel, String actionCode) {
        var exception = CopybotException.wrapIfNeeded(cause);
        return CopybotException.ofResource(exception, "action.error", actionLabel, actionCode, exception.getMessage());
    }

}