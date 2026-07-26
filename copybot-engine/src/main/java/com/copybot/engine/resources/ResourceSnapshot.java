package com.copybot.engine.resources;

/** Point-in-time view of one resource, for UI display. */
public record ResourceSnapshot(String name, int capacity, int used, int waiting) {
}
