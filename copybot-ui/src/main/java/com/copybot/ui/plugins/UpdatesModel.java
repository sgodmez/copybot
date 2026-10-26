package com.copybot.ui.plugins;

import com.copybot.ui.update.Release;
import com.copybot.utils.VersionUtil;

import java.util.ArrayList;
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
    }

    private final Optional<Release> release;
    private final List<Plugin> plugins;
    private final Set<String> checkedModules = new LinkedHashSet<>();
    private boolean appChecked;
    private long appSize;

    /**
     * @param release       the newer release offered, empty when none
     * @param installedRows the installed plugins of the application's plugins folder, in catalogue order (up to date
     *                      ones included)
     * @param apiVersion    this Copybot's plugin API (never the application version); null = unknown, accepts all
     */
    public UpdatesModel(Optional<Release> release, List<PluginRow> installedRows, String apiVersion) {
        this.release = release;
        List<Plugin> lines = new ArrayList<>();
        for (PluginRow row : installedRows) {
            lines.add(new Plugin(row, line(row, release.isPresent(), apiVersion)));
        }
        this.plugins = List.copyOf(lines);
        this.appChecked = release.isPresent();
        for (Plugin plugin : plugins) {
            // everything that can be updated starts ticked, the linked plugins included (the application is ticked)
            if (plugin.line() == PluginLine.UPDATABLE || plugin.line() == PluginLine.NEEDS_APP) {
                checkedModules.add(module(plugin.row()));
            }
        }
    }

    private static PluginLine line(PluginRow row, boolean releaseOffered, String apiVersion) {
        boolean newer = row.installedVersion() != null
                && VersionUtil.VERSION_ORDER.compare(row.installedVersion(), row.entry().version()) < 0;
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

    /** The plugin lines, in catalogue order. */
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
     * Copybot, and not for a linked one while the application is unticked (ticking it then ticks the application, see
     * {@link #setChecked}).
     */
    public boolean enabled(PluginRow row) {
        return switch (lineOf(row)) {
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
        }
    }

    /**
     * Ticks or unticks a plugin. Ticking a linked one ticks the application too; an up-to-date plugin or one waiting
     * for a newer Copybot cannot be ticked.
     */
    public void setChecked(PluginRow row, boolean checked) {
        PluginLine line = lineOf(row);
        if (line == PluginLine.UP_TO_DATE || line == PluginLine.NEEDS_NEWER_APP) {
            return;
        }
        if (!checked) {
            checkedModules.remove(module(row));
            return;
        }
        if (line == PluginLine.NEEDS_APP) {
            appChecked = true;
        }
        checkedModules.add(module(row));
    }

    /** The modules of the ticked plugins. */
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

    private PluginLine lineOf(PluginRow row) {
        return plugins.stream().filter(p -> module(p.row()).equals(module(row))).findFirst()
                .map(Plugin::line).orElse(PluginLine.UP_TO_DATE);
    }
}
