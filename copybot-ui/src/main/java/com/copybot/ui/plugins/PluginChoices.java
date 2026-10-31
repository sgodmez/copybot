package com.copybot.ui.plugins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The actions chosen in the Plugins window, by module, until they are applied together (spec plugin-install §3). */
public final class PluginChoices {

    /** What can be done with an official plugin. */
    public enum Action {
        INSTALL, UPDATE, REMOVE
    }

    private final Map<String, Action> chosen = new LinkedHashMap<>();

    /**
     * The actions a row offers: install what is not installed, update what has a compatible update, remove what is
     * installed in the plugins folder (whatever its state). A plugin for another engine offers nothing.
     */
    public static List<Action> offered(PluginRow row) {
        List<Action> actions = new ArrayList<>();
        switch (row.state()) {
            case NOT_INSTALLED -> actions.add(Action.INSTALL);
            case UPDATE_AVAILABLE -> actions.add(Action.UPDATE);
            default -> {
            }
        }
        if (row.state() != PluginState.NOT_INSTALLED && row.state() != PluginState.INCOMPATIBLE
                && !row.installedFolders().isEmpty()) {
            actions.add(Action.REMOVE);
        }
        return List.copyOf(actions);
    }

    public Optional<Action> chosen(PluginRow row) {
        return Optional.ofNullable(chosen.get(row.entry().module()));
    }

    /** Chooses an action the row offers, in place of another one. */
    public void choose(PluginRow row, Action action) {
        if (!offered(row).contains(action)) {
            throw new IllegalArgumentException(action + " is not offered for " + row.entry().code());
        }
        chosen.put(row.entry().module(), action);
    }

    public void cancel(PluginRow row) {
        chosen.remove(row.entry().module());
    }

    public boolean isEmpty() {
        return chosen.isEmpty();
    }

    /** "Update all": every compatible update is chosen. @return the number of updates */
    public int updateAll(List<PluginRow> rows) {
        int count = 0;
        for (PluginRow row : rows) {
            if (row.state() == PluginState.UPDATE_AVAILABLE) {
                choose(row, Action.UPDATE);
                count++;
            }
        }
        return count;
    }

    /**
     * Chooses the install or the update of these modules (a missing plugin), where offered; the choices already made
     * are kept, a module chosen already included.
     */
    public void preselect(List<PluginRow> rows, Collection<String> modules) {
        for (PluginRow row : rows) {
            if (modules.contains(row.entry().module()) && chosen(row).isEmpty()) {
                List<Action> offered = offered(row);
                if (offered.contains(Action.INSTALL)) {
                    choose(row, Action.INSTALL);
                } else if (offered.contains(Action.UPDATE)) {
                    choose(row, Action.UPDATE);
                }
            }
        }
    }

    /** Keeps only the choices the new rows still offer (after a refresh of the catalogue or of the plugins). */
    public void retain(List<PluginRow> rows) {
        Map<String, PluginRow> byModule = new LinkedHashMap<>();
        rows.forEach(row -> byModule.put(row.entry().module(), row));
        chosen.entrySet().removeIf(choice -> {
            PluginRow row = byModule.get(choice.getKey());
            return row == null || !offered(row).contains(choice.getValue());
        });
    }

    /** The rows chosen to be installed or updated, in row order. */
    public List<PluginRow> toInstallOrUpdate(List<PluginRow> rows) {
        return rows.stream().filter(row -> chosen(row).filter(a -> a != Action.REMOVE).isPresent()).toList();
    }

    /** The rows chosen to be uninstalled, in row order. */
    public List<PluginRow> toRemove(List<PluginRow> rows) {
        return rows.stream().filter(row -> chosen(row).filter(a -> a == Action.REMOVE).isPresent()).toList();
    }
}
