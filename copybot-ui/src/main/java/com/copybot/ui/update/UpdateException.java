package com.copybot.ui.update;

/**
 * A failed update check or download. It carries a message key of the UI bundles ({@code update.error.*}) and its
 * arguments rather than a text, the UI translates it.
 */
public final class UpdateException extends Exception {

    private final String key;
    private final Object[] args;

    public UpdateException(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args.clone();
    }

    public String key() {
        return key;
    }

    public Object[] args() {
        return args.clone();
    }
}
