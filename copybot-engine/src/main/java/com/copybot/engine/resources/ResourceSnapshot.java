package com.copybot.engine.resources;

/** Point-in-time view of one resource, for UI display; paused is the registry-wide pause flag. */
public record ResourceSnapshot(String name, int capacity, int used, int waiting, boolean paused) {
}
