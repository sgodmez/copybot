package com.copybot.engine.resources;

/**
 * What holds a path: a physical disk ("PhysicalDrive1", "sda") or, when it cannot be found, the volume
 * ("D:\", "/home"). The id is the resource name without its "disk:" prefix.
 */
record DiskIdentity(String id, DiskKind kind) {
}
