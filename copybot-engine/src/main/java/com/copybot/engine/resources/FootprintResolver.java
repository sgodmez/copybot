package com.copybot.engine.resources;

import com.copybot.engine.pipeline.PipelineStepConfig;
import com.copybot.plugin.api.action.IAction;
import com.copybot.plugin.api.action.WorkItem;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Computes the resource footprint of one (action, item) execution by merging:
 * 1. what the action declares (requiredResources, touchedPaths),
 * 2. what the engine detects (disk of the item's current location),
 * 3. what the user configured (extra resources, maxConcurrency as an implicit step resource).
 */
public final class FootprintResolver {

    private FootprintResolver() {
    }

    /** Footprint of an IN step's listing (no item yet: touchedPaths is called with null). */
    public static Set<String> forListing(IAction action, PipelineStepConfig config) {
        return merge(action, null, config, -1);
    }

    /**
     * Footprint of a look at the targets of an item (the plan's conflict check): the disks the action touches, not
     * the disk of the item itself, which the look does not read.
     */
    public static Set<String> forTargets(IAction action, WorkItem item) {
        Set<String> footprint = new HashSet<>();
        for (Path path : action.touchedPaths(item)) {
            footprint.add(DiskResolver.diskResource(path));
        }
        return footprint;
    }

    /** Footprint of one step execution for one item. */
    public static Set<String> resolve(IAction action, WorkItem item, PipelineStepConfig config, int stepIndex) {
        return merge(action, item, config, stepIndex);
    }

    private static Set<String> merge(IAction action, WorkItem item, PipelineStepConfig config, int stepIndex) {
        Set<String> footprint = new HashSet<>(action.requiredResources(item));
        for (Path path : action.touchedPaths(item)) {
            footprint.add(DiskResolver.diskResource(path));
        }
        if (item != null && item.isLocal()) {
            footprint.add(DiskResolver.diskResource(item.getLocalLocation()));
        }
        if (config != null) {
            if (config.resources() != null) {
                footprint.addAll(config.resources());
            }
            if (config.maxConcurrency() != null && stepIndex >= 0) {
                footprint.add("step:" + stepIndex);
            }
        }
        return footprint;
    }
}
