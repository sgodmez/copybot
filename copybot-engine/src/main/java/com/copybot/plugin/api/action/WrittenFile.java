package com.copybot.plugin.api.action;

import java.nio.file.Path;

/**
 * A file written and verified by the run, a main file or a companion.
 *
 * @param target the written file
 * @param sha256 its SHA-256, lower-case hexadecimal
 * @param size   its size in bytes
 */
public record WrittenFile(Path target, String sha256, long size) {
}
