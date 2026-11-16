package com.copybot.ui.plugins;

import com.copybot.ui.update.Release;
import com.copybot.utils.VersionUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the Updates window shows and what is ticked, without any JavaFX so that the rules can be tested: the
 * application line, the official plugin lines, the link between the application and the plugins built for its next
 * plugin API, and what the "Update" button applies.
 *
 * <p>The link exists because a plugin built for a newer plugin API must never be applied alone: it would not load (or
 * worse) in the installed Copybot. Such a plugin can only travel with the application update.
 *
 * <p>The plugins other plugins need (ffmpeg needs exec) are followed too: ticking a plugin ticks the update of a
 * plugin it needs, or its install when it is not installed (a line of its own, which follows the plugins that need
 * it); a plugin whose needed plugin cannot come stays unticked, and {@link #waitingFor} tells why.
 */
public final class UpdatesModel {

    /** How a plugin line behaves. */
    public enum PluginLine {
        /** Nothing newer in the catalogue: shown, never ticked. */
        UP_TO_DATE,
        /** A newer version this Copybot accepts: freely ticked, with or without the application. */
        UPDATABLE,
        /** A newer version built for a newer plugin API, and a release is offered: travels with the application. */
        NEEDS_APP,
        /** Same, but no release is offered: nothing can be done for now. */
        NEEDS_NEWER_APP
    }

    /** A plugin line: the row it comes from and how it behaves. */
    public record Plugin(PluginRow row, PluginLine line) {

        /**
         * True for a plugin that is not installed: one an installed plugin needs, installed along with it while a
         * plugin that needs it is ticked; never ticked by itself.
         */
        public boolean install() {
            return row.installedVersion() == null;
        }
    }

    /**
     * Why a plugin cannot be ticked: a plugin it needs that cannot come with it.
     *
     * @param requirement the plugin needed and its version
     * @param newerApp    true when the catalogue has that version, but built for a newer Copybot than any offered
     */
    public record Waiting(PluginCatalog.Requirement requirement, boolean newerApp) {
    }

    private final Optional<Release> release;
    private final List<Plugin> plugins;
    /** Every row of the catalogue: where the installed version of a needed plugin is read. */
    private final List<PluginRow> catalogueRows;
    private final Set<String> checkedModules = new LinkedHashSet<>();
    private boolean appChecked;
    private long appSize;

    /** Without the rows of the plugins that are not installed: no plugin can be installed along. */
    public UpdatesModel(Optional<Release> release, List<PluginRow> installedRows, String apiVersion) {
        this(release, installedRows, installedRows, apiVersion);
    }

    /**
     * @param release       the newer release offered, empty when none
     * @param installedRows the installed plugins of the application's plugins folder, in catalogue order (up to date
     *                      ones included)
     * @param catalogueRows every row of the catalogue, installed or not: the plugins not installed that an installed
     *                      one needs become lines too, after the installed ones
     * @param apiVersion    this Copybot's plugin API (never the application version); null = unknown, accepts all
     */
    public UpdatesModel(Optional<Release> release, List<PluginRow> installedRows, List<PluginRow> catalogueRows,
                        String apiVersion) {
        this.release = release;
        this.catalogueRows = List.copyOf(catalogueRows);
        List<Plugin> lines = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (PluginRow row : installedRows) {
            lines.add(new Plugin(row, line(row, release.isPresent(), apiVersion)));
            codes.add(row.entry().code());
        }
        Deque<PluginRow> toFollow = new ArrayDeque<>(installedRows);
        while (!toFollow.isEmpty()) {
            for (PluginCatalog.Requirement requirement : toFollow.poll().entry().requires()) {
                if (codes.contains(requirement.code())) {
                    continue;
                }
                Optional<PluginRow> needed = catalogueRows.stream()
                        .filter(row -> row.entry().code().equals(requirement.code()) && row.installedVersion() == null)
                        .findFirst();
                if (needed.isPresent()) {
                    codes.add(requirement.code());
                    lines.add(new Plugin(needed.get(), line(needed.get(), release.isPresent(), apiVersion)));
                    toFollow.add(needed.get());
                }
            }
        }
        this.plugins = List.copyOf(lines);
        this.appChecked = release.isPresent();
        for (Plugin plugin : plugins) {
            // everything that can be updated starts ticked, the linked plugins included (the application is ticked)
            if (!plugin.install() && (plugin.line() == PluginLine.UPDATABLE || plugin.line() == PluginLine.NEEDS_APP)) {
                checkedModules.add(module(plugin.row()));
            }
        }
        for (Plugin plugin : plugins) {
            if (checkedModules.contains(module(plugin.row()))) {
                tickRequirements(plugin.row());
            }
        }
        dropUnmet();
    }

    private static PluginLine line(PluginRow row, boolean releaseOffered, String apiVersion) {
        boolean newer = row.installedVersion() == null
                || VersionUtil.VERSION_ORDER.compare(row.installedVersion(), row.entry().version()) < 0;
        if (!newer) {
            return PluginLine.UP_TO_DATE;
        }
        // an unknown API version refuses nothing, as the loader does
        if (apiVersion == null || VersionUtil.isCompatible(apiVersion, row.entry().engine(), false)) {
            return PluginLine.UPDATABLE;
        }
        return releaseOffered ? PluginLine.NEEDS_APP : PluginLine.NEEDS_NEWER_APP;
    }

    private static String module(PluginRow row) {
        return row.entry().module();
    }

    /** The newer release offered, empty when none. */
    public Optional<Release> release() {
        return release;
    }

    /** The plugin lines: the installed plugins in catalogue order, then the plugins they need that are not. */
    public List<Plugin> plugins() {
        return plugins;
    }

    /** True when the application update is ticked (never without an offered release). */
    public boolean appChecked() {
        return appChecked;
    }

    /** True when this plugin is ticked. */
    public boolean checked(PluginRow row) {
        return checkedModules.contains(module(row));
    }

    /**
     * True when the user can tick or untick this plugin: not for an up-to-date one nor one waiting for a newer
     * Copybot, not for a linked one while the application is unticked (ticking it then ticks the application, see
     * {@link #setChecked}), not for a plugin installed along, and not for one waiting for a plugin it needs.
     */
    public boolean enabled(PluginRow row) {
        Optional<Plugin> plugin = lineOfModule(module(row));
        if (plugin.isEmpty() || plugin.get().install() || waitingFor(row).isPresent()) {
            return false;
        }
        return switch (plugin.get().line()) {
            case UPDATABLE -> true;
            case NEEDS_APP -> appChecked;
            case UP_TO_DATE, NEEDS_NEWER_APP -> false;
        };
    }

    /** Ticks or unticks the application; unticking it unticks every linked plugin. No-op without an offered release. */
    public void setAppChecked(boolean checked) {
        if (release.isEmpty()) {
            return;
        }
        appChecked = checked;
        if (!checked) {
            plugins.stream().filter(p -> p.line() == PluginLine.NEEDS_APP)
                    .forEach(p -> checkedModules.remove(module(p.row())));
            dropUnmet();
        }
    }

    /**
     * Ticks or unticks a plugin. Ticking a linked one ticks the application too; an up-to-date plugin, one waiting
     * for a newer Copybot or one installed along cannot be ticked. Ticking a plugin ticks the updates (or installs) of
     * the plugins it needs when the installed version does not meet its requirement (ffmpeg 2 needing exec 3);
     * unticking one unticks the ticked plugins that need it. A plugin whose requirement cannot come stays unticked.
     */
    public void setChecked(PluginRow row, boolean checked) {
        Optional<Plugin> plugin = lineOfModule(module(row));
        if (plugin.isEmpty() || plugin.get().install()) {
            return;
        }
        PluginLine line = plugin.get().line();
        if (line == PluginLine.UP_TO_DATE || line == PluginLine.NEEDS_NEWER_APP) {
            return;
        }
        if (!checked) {
            checkedModules.remove(module(row));
            dropUnmet();
            return;
        }
        if (line == PluginLine.NEEDS_APP) {
            appChecked = true;
        }
        checkedModules.add(module(row));
        tickRequirements(row);
        dropUnmet();
    }

    /**
     * Why this plugin cannot be ticked: the first plugin it needs that is not installed in a version that works and
     * whose catalogue version that would work cannot come (absent, too old, or waiting for a newer Copybot). Empty
     * when every plugin it needs is there or can come with it.
     */
    public Optional<Waiting> waitingFor(PluginRow row) {
        for (PluginCatalog.Requirement requirement : row.entry().requires()) {
            if (PluginDependencies.satisfies(installedVersion(requirement.code()), requirement.version())) {
                continue;
            }
            Optional<Plugin> needed = lineOf(requirement.code());
            if (needed.isPresent() && obtainable(needed.get(), requirement)) {
                continue;
            }
            boolean newerApp = needed.isPresent() && needed.get().line() == PluginLine.NEEDS_NEWER_APP
                    && PluginDependencies.satisfies(needed.get().row().entry().version(), requirement.version());
            return Optional.of(new Waiting(requirement, newerApp));
        }
        return Optional.empty();
    }

    /** The codes of the plugins of the lines that need this one, in the order of the lines. */
    public List<String> neededBy(PluginRow row) {
        String code = row.entry().code();
        return plugins.stream().filter(p -> p.row().entry().requires().stream().anyMatch(r -> r.code().equals(code)))
                .map(p -> p.row().entry().code()).toList();
    }

    /** True when the catalogue version of this line meets the requirement and the line can be ticked. */
    private static boolean obtainable(Plugin needed, PluginCatalog.Requirement requirement) {
        return (needed.line() == PluginLine.UPDATABLE || needed.line() == PluginLine.NEEDS_APP)
                && PluginDependencies.satisfies(needed.row().entry().version(), requirement.version());
    }

    /** Ticks, through their requirements, the updates and installs the plugin needs. */
    private void tickRequirements(PluginRow row) {
        for (PluginCatalog.Requirement requirement : row.entry().requires()) {
            if (PluginDependencies.satisfies(installedVersion(requirement.code()), requirement.version())) {
                continue;
            }
            Optional<Plugin> needed = lineOf(requirement.code());
            if (needed.isEmpty() || checkedModules.contains(module(needed.get().row()))
                    || !obtainable(needed.get(), requirement)) {
                continue;
            }
            if (needed.get().line() == PluginLine.NEEDS_APP) {
                appChecked = true;
            }
            checkedModules.add(module(needed.get().row()));
            tickRequirements(needed.get().row());
        }
    }

    /**
     * Unticks, until none is left: every ticked plugin that needs a plugin neither installed in a version that works
     * nor ticked in such a version (it would not load after the update), and every plugin installed along that no
     * ticked plugin needs any more.
     */
    private void dropUnmet() {
        boolean dropped;
        do {
            dropped = false;
            for (Plugin plugin : plugins) {
                String module = module(plugin.row());
                boolean drop = unmet(plugin.row()) || plugin.install() && !neededByATicked(plugin);
                if (checkedModules.contains(module) && drop) {
                    checkedModules.remove(module);
                    dropped = true;
                }
            }
        } while (dropped);
    }

    private boolean neededByATicked(Plugin plugin) {
        String code = plugin.row().entry().code();
        return plugins.stream().anyMatch(p -> checkedModules.contains(module(p.row()))
                && p.row().entry().requires().stream().anyMatch(r -> r.code().equals(code)));
    }

    private boolean unmet(PluginRow row) {
        for (PluginCatalog.Requirement requirement : row.entry().requires()) {
            Optional<Plugin> needed = lineOf(requirement.code());
            if (needed.isPresent() && checkedModules.contains(module(needed.get().row()))) {
                if (!PluginDependencies.satisfies(needed.get().row().entry().version(), requirement.version())) {
                    return true;
                }
            } else if (!PluginDependencies.satisfies(installedVersion(requirement.code()), requirement.version())) {
                return true; // not installed (and not ticked for install), or too old (and its update not ticked)
            }
        }
        return false;
    }

    /** The installed version of the plugin of this code, wherever it is installed; null when it is not. */
    private String installedVersion(String code) {
        return catalogueRows.stream().filter(row -> row.entry().code().equals(code) && row.installedVersion() != null)
                .map(PluginRow::installedVersion).findFirst()
                .or(() -> plugins.stream().map(Plugin::row)
                        .filter(row -> row.entry().code().equals(code) && row.installedVersion() != null)
                        .map(PluginRow::installedVersion).findFirst())
                .orElse(null);
    }

    private Optional<Plugin> lineOf(String code) {
        return plugins.stream().filter(p -> p.row().entry().code().equals(code)).findFirst();
    }

    private Optional<Plugin> lineOfModule(String module) {
        return plugins.stream().filter(p -> module(p.row()).equals(module)).findFirst();
    }

    /** The modules of the ticked plugins, those installed along included. */
    public Set<String> checkedModules() {
        return new HashSet<>(checkedModules);
    }

    /** True when neither the application nor any plugin is ticked. */
    public boolean nothingToApply() {
        return !appChecked && checkedModules.isEmpty();
    }

    /**
     * The download size known for what is ticked: the application asset's size. The catalogue does not give the
     * archive size of the plugins, so they count for nothing here. 0 when none is known.
     */
    public long knownSize() {
        return appChecked ? appSize : 0;
    }

    /** The size of the application asset that applies to this machine (known by the controller, not the model). */
    public void setAppSize(long bytes) {
        this.appSize = bytes;
    }
}
