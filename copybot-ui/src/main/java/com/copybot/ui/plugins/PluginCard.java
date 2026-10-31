package com.copybot.ui.plugins;

import com.copybot.engine.pipeline.StepType;
import com.copybot.engine.plugin.report.ActionEntry;
import com.copybot.engine.plugin.report.PluginEntry;
import com.copybot.engine.plugin.report.PluginReport;
import com.copybot.engine.plugin.report.PluginSource;
import com.copybot.engine.plugin.report.PluginStatus;
import com.copybot.utils.VersionUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What one card of the Plugins window shows (spec updates-window, "Refonte de l'écran Plugins"): an official plugin
 * of the catalogue, installed or not, or a plugin loaded that the catalogue does not list, with what was loaded of it.
 * No JavaFX and no texts: the window translates.
 *
 * @param title  the code of the catalogue, the module name for a plugin the catalogue does not list
 * @param module the module name, what the "plugin" of a pipeline step names
 * @param row    the official plugin, null for a plugin the catalogue does not list (or the embedded one)
 * @param loaded what was found of this module at startup, in the order of the report; empty when not installed
 */
public record PluginCard(String title, String module, PluginRow row, List<PluginEntry> loaded) {

    /** Where the plugin stands, for the state shown in its header and its colour. */
    public enum Status {
        /** Official, installed, the version of the catalogue (or a newer one it cannot take yet), and loaded. */
        UP_TO_DATE,
        /** Official, installed, a compatible newer version in the catalogue. */
        UPDATE_AVAILABLE,
        /** Official, not installed, can be installed. */
        AVAILABLE,
        /** Official, made for another plugin API (installed and refused, or not installed). */
        INCOMPATIBLE,
        /** Installed, but it does not load (nothing of it loaded, an error). */
        FAILED,
        /** Outside the catalogue, loaded. */
        LOADED,
        /** Outside the catalogue, found but not loaded on purpose (a duplicate, an older revision). */
        IGNORED
    }

    /** The cards shown by the filters at the top of the window. */
    public enum Filter {
        ALL, INSTALLED, AVAILABLE
    }

    /**
     * The buttons of a card: those of {@link PluginChoices.Action}, and the Updates window in place of the update of
     * an installed plugin.
     */
    public enum Action {
        INSTALL, UPDATE, REMOVE, OPEN_UPDATES;

        /** The choice this button makes in the window, empty for {@link #OPEN_UPDATES}. */
        public Optional<PluginChoices.Action> choice() {
            return switch (this) {
                case INSTALL -> Optional.of(PluginChoices.Action.INSTALL);
                case UPDATE -> Optional.of(PluginChoices.Action.UPDATE);
                case REMOVE -> Optional.of(PluginChoices.Action.REMOVE);
                case OPEN_UPDATES -> Optional.empty();
            };
        }
    }

    /**
     * An action the plugin provides.
     *
     * @param code what the "action" of a pipeline step writes
     * @param name the translated name, the code when the plugin has none
     */
    public record ActionLine(StepType type, String code, String name) {

        /** The plugin translates its name: shown with the code, else the code alone. */
        public boolean named() {
            return name != null && !name.isBlank() && !name.equals(code);
        }
    }

    public PluginCard {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(module, "module");
        loaded = List.copyOf(loaded);
    }

    /**
     * The cards of the window: one per official plugin in catalogue order, then one per plugin loaded that the
     * catalogue does not list, in the order of the report. The embedded plugin has its own ({@link #embedded}).
     */
    public static List<PluginCard> of(List<PluginRow> rows, PluginReport report) {
        Map<String, List<PluginEntry>> byModule = new LinkedHashMap<>();
        for (PluginEntry entry : report.plugins()) {
            if (entry.source() != PluginSource.EMBEDDED) {
                byModule.computeIfAbsent(entry.name(), name -> new ArrayList<>()).add(entry);
            }
        }
        List<PluginCard> cards = new ArrayList<>();
        Set<String> official = new LinkedHashSet<>();
        for (PluginRow row : rows) {
            official.add(row.entry().module());
            cards.add(new PluginCard(row.entry().code(), row.entry().module(), row,
                    byModule.getOrDefault(row.entry().module(), List.of())));
        }
        byModule.forEach((module, entries) -> {
            if (!official.contains(module)) {
                cards.add(new PluginCard(module, module, null, entries));
            }
        });
        return List.copyOf(cards);
    }

    /** The card of the plugin built into Copybot, empty when the report has none. */
    public static Optional<PluginCard> embedded(PluginReport report) {
        List<PluginEntry> entries = report.plugins().stream().filter(p -> p.source() == PluginSource.EMBEDDED)
                .toList();
        return entries.isEmpty() ? Optional.empty()
                : Optional.of(new PluginCard(entries.get(0).name(), entries.get(0).name(), null, entries));
    }

    /** A plugin of the catalogue: it can be installed, updated or removed from here. */
    public boolean official() {
        return row != null;
    }

    /** The plugin built into Copybot. */
    public boolean isEmbedded() {
        return loaded.stream().anyMatch(p -> p.source() == PluginSource.EMBEDDED);
    }

    /** The installed version (the most recent found), else the version of the catalogue; null when none is known. */
    public String version() {
        if (row != null) {
            return row.installedVersion() != null ? row.installedVersion() : row.entry().version();
        }
        return loaded.stream().map(PluginEntry::version).filter(Objects::nonNull)
                .max(VersionUtil.VERSION_ORDER).orElse(null);
    }

    /** Installed: found at startup, or known installed by the catalogue row. */
    public boolean installed() {
        return !loaded.isEmpty() || (row != null && row.installedVersion() != null);
    }

    public Status status() {
        boolean working = loaded.stream().anyMatch(p -> p.status() == PluginStatus.LOADED);
        boolean failing = loaded.stream().anyMatch(PluginCard::failed);
        if (row != null) {
            return switch (row.state()) {
                case NOT_INSTALLED -> Status.AVAILABLE;
                case INCOMPATIBLE, INSTALLED_INCOMPATIBLE -> Status.INCOMPATIBLE;
                case UPDATE_AVAILABLE -> Status.UPDATE_AVAILABLE;
                case UP_TO_DATE -> !working && failing ? Status.FAILED : Status.UP_TO_DATE;
            };
        }
        if (working) {
            return Status.LOADED;
        }
        return failing ? Status.FAILED : Status.IGNORED;
    }

    /** The actions provided, known only for what was loaded; each once, in the order of the report. */
    public List<ActionLine> actions() {
        Set<ActionLine> lines = new LinkedHashSet<>();
        for (PluginEntry entry : loaded) {
            if (entry.status() == PluginStatus.LOADED) {
                for (ActionEntry action : entry.actions()) {
                    lines.add(new ActionLine(action.type(), action.code(), action.name()));
                }
            }
        }
        return List.copyOf(lines);
    }

    /**
     * What the "plugin" of a pipeline step writes for these actions: the module; empty for the embedded plugin,
     * whose steps leave it out.
     */
    public Optional<String> pipelinePlugin() {
        return isEmbedded() ? Optional.empty() : Optional.of(module);
    }

    /** The plugins it is built on ("name version"), empty when none. */
    public List<String> dependsOn() {
        return distinct(loaded.stream().flatMap(p -> p.pluginDependencies().stream()).toList());
    }

    /** The modules it requires that nothing provides, empty when none. */
    public List<String> missing() {
        return distinct(loaded.stream().flatMap(p -> p.missingRequires().stream()).toList());
    }

    /** Why it does not load (an error, actions that cannot be listed), translated by the engine; empty when none. */
    public List<String> loadErrors() {
        return distinct(loaded.stream().filter(PluginCard::failed).map(PluginEntry::message)
                .filter(Objects::nonNull).toList());
    }

    /** Shown by the filter: installed means found or known installed, available means official and not installed. */
    public boolean shownBy(Filter filter) {
        return switch (filter) {
            case ALL -> true;
            case INSTALLED -> installed();
            case AVAILABLE -> official() && !installed();
        };
    }

    /**
     * The buttons of the card, before any choice. An installed plugin with an update leads to the Updates window,
     * where the application and its plugins are updated together; but a plugin a pipeline asked for ({@code repair}:
     * preselected because it is missing or refused) is updated here, as it is installed here. A plugin the catalogue
     * does not list offers nothing.
     */
    public List<Action> offered(boolean repair) {
        if (row == null) {
            return List.of();
        }
        List<Action> actions = new ArrayList<>();
        for (PluginChoices.Action action : PluginChoices.offered(row)) {
            actions.add(switch (action) {
                case INSTALL -> Action.INSTALL;
                case UPDATE -> repair && row.offersInstall() ? Action.UPDATE : Action.OPEN_UPDATES;
                case REMOVE -> Action.REMOVE;
            });
        }
        return List.copyOf(actions);
    }

    private static boolean failed(PluginEntry entry) {
        return entry.status() == PluginStatus.ERROR || entry.status() == PluginStatus.ACTIONS_FAILED;
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
