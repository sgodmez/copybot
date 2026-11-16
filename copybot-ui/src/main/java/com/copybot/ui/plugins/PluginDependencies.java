package com.copybot.ui.plugins;

import com.copybot.utils.VersionUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The plugins that other plugins need, from the {@code requires} of the catalogue (ffmpeg needs exec): what an
 * install or an update brings along, what cannot be met, and what a removal leaves without a plugin it needs. No
 * JavaFX, so that the rules can be tested.
 */
public final class PluginDependencies {

    private PluginDependencies() {
    }

    /** A requirement no version of the catalogue meets: the change that needs it cannot be applied. */
    public record Unavailable(PluginRow row, PluginCatalog.Requirement requirement) {
    }

    /** A plugin left without one it needs, by a removal: it would not load any more. */
    public record Broken(PluginRow dependent, PluginRow removed) {
    }

    /**
     * What applying the choices does once the requirements are followed.
     *
     * @param installOrUpdate the plugins to install or update: the chosen ones, then those they need
     * @param added           the plugins installed or updated only because a chosen one needs them
     * @param remove          the plugins to remove (as chosen)
     * @param unavailable     the requirements that no version of the catalogue meets
     * @param broken          the installed (or chosen) plugins a removal leaves without a plugin they need
     * @param neededRemoved   the requirements of a plugin to install or update on a plugin removed in the same
     *                        changes: the changes are refused, like an unavailable requirement
     */
    public record Plan(List<PluginRow> installOrUpdate, List<PluginRow> added, List<PluginRow> remove,
                       List<Unavailable> unavailable, List<Broken> broken, List<Unavailable> neededRemoved) {

        public Plan {
            installOrUpdate = List.copyOf(installOrUpdate);
            added = List.copyOf(added);
            remove = List.copyOf(remove);
            unavailable = List.copyOf(unavailable);
            broken = List.copyOf(broken);
            neededRemoved = List.copyOf(neededRemoved);
        }

        /** True when the changes cannot be applied: a requirement unavailable, or on a plugin removed meanwhile. */
        public boolean refused() {
            return !unavailable.isEmpty() || !neededRemoved.isEmpty();
        }
    }

    /**
     * True when this version meets the requirement: the same major version, and at least the required one (the
     * loader's rule, with the revision counted too). A null version (not installed) meets nothing.
     */
    public static boolean satisfies(String version, String required) {
        return version != null && VersionUtil.isCompatible(version, required, false)
                && VersionUtil.VERSION_ORDER.compare(version, required) >= 0;
    }

    /**
     * Follows the requirements of the chosen installs and updates: a needed plugin that is installed in a version
     * that works is left as it is; otherwise it is installed or updated along with them when the catalogue offers a
     * version that works, else the requirement is unavailable; a needed plugin chosen to be removed refuses the
     * changes ({@link Plan#neededRemoved}). Then names the plugins a removal breaks.
     *
     * @param rows     every row of the catalogue
     * @param chosen   the plugins chosen to be installed or updated
     * @param toRemove the plugins chosen to be removed
     */
    public static Plan plan(List<PluginRow> rows, List<PluginRow> chosen, List<PluginRow> toRemove) {
        Map<String, PluginRow> byCode = new LinkedHashMap<>();
        rows.forEach(row -> byCode.put(row.entry().code(), row));
        Set<String> removed = toRemove.stream().map(row -> row.entry().code()).collect(Collectors.toSet());
        Map<String, PluginRow> installs = new LinkedHashMap<>();
        chosen.forEach(row -> installs.put(row.entry().code(), row));
        List<PluginRow> added = new ArrayList<>();
        List<Unavailable> unavailable = new ArrayList<>();
        List<Unavailable> neededRemoved = new ArrayList<>();

        Deque<PluginRow> toFollow = new ArrayDeque<>(chosen);
        while (!toFollow.isEmpty()) {
            PluginRow row = toFollow.poll();
            for (PluginCatalog.Requirement requirement : row.entry().requires()) {
                PluginRow needed = byCode.get(requirement.code());
                if (needed == null) {
                    unavailable.add(new Unavailable(row, requirement));
                } else if (installs.containsKey(requirement.code())) {
                    if (!satisfies(needed.entry().version(), requirement.version())) {
                        unavailable.add(new Unavailable(row, requirement));
                    }
                } else if (!removed.contains(requirement.code())
                        && satisfies(needed.installedVersion(), requirement.version())) {
                    // installed in a version that works: nothing to do
                } else if (removed.contains(requirement.code())) {
                    // removed although a plugin to install or update needs it: the changes contradict each other
                    neededRemoved.add(new Unavailable(row, requirement));
                } else if ((needed.state() == PluginState.NOT_INSTALLED || needed.state() == PluginState.UPDATE_AVAILABLE)
                        && satisfies(needed.entry().version(), requirement.version())) {
                    installs.put(requirement.code(), needed);
                    added.add(needed);
                    toFollow.add(needed);
                } else {
                    unavailable.add(new Unavailable(row, requirement));
                }
            }
        }

        List<Broken> broken = new ArrayList<>();
        for (PluginRow removal : toRemove) {
            for (PluginRow row : rows) {
                boolean stays = installs.containsKey(row.entry().code())
                        || row.installedVersion() != null && !removed.contains(row.entry().code());
                if (stays && row.entry().requires().stream()
                        .anyMatch(r -> r.code().equals(removal.entry().code()))) {
                    broken.add(new Broken(row, removal));
                }
            }
        }
        return new Plan(List.copyOf(installs.values()), added, toRemove, unavailable, broken, neededRemoved);
    }
}
