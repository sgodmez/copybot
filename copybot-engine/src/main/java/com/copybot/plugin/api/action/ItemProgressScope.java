package com.copybot.plugin.api.action;

/** Holds the scoped value behind {@link ItemProgress#current()}: bound only through {@link ItemProgress#callWith}. */
final class ItemProgressScope {

    static final ScopedValue<ItemProgress> CURRENT = ScopedValue.newInstance();

    private ItemProgressScope() {
    }
}
