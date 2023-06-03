package com.copybot.plugin.exception;

public class ProcessFailedException extends RuntimeException {

    public ProcessFailedException(String message) {
        super(message);
    }

    public ProcessFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProcessFailedException(Throwable cause) {
        super(cause);
    }
}
