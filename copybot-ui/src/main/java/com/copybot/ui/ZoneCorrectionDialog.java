package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.ZoneLabels;
import com.copybot.ui.model.ZoneLabels.Entry;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** The time zone dialog of the plan view ("Correct the time zone…"): where the photos were taken, and the camera clock. */
final class ZoneCorrectionDialog {

    /** A choice of the dialog: the place of the shots, and the zone the camera clock was set to (null: the one of the place). */
    record ZoneChoice(ZoneId place, ZoneId clock) {
    }

    private ZoneCorrectionDialog() {
    }

    /** A text filter above a list of zones, the list showing labels with the UTC offset. */
    private static ListView<Entry> filterableList(List<Entry> entries, VBox into, double height) {
        TextField filter = new TextField();
        filter.setPromptText(ResourcesEngine.getString("zone.dialog.filter"));
        FilteredList<Entry> shown = new FilteredList<>(FXCollections.observableArrayList(entries));
        filter.textProperty().addListener((o, was, text) -> shown.setPredicate(entry -> ZoneLabels.matches(entry, text)));
        ListView<Entry> list = new ListView<>(shown);
        list.setPrefHeight(height);
        into.getChildren().addAll(filter, list);
        return list;
    }

    private static void select(ListView<Entry> list, ZoneId zone) {
        list.getItems().stream().filter(e -> e.zone().equals(zone)).findFirst().ifPresent(entry -> {
            list.getSelectionModel().select(entry);
            list.scrollTo(entry);
        });
    }

    /**
     * Filterable lists of the zones; for photos without a recorded zone, also the zone the camera was set to.
     *
     * @param initial           the zone selected first
     * @param clockUnknownCount the selected photos without a recorded zone: the camera clock is asked when there are some
     * @param clockInitial      the camera clock selected first (the place without summer time, or a zone)
     */
    static Dialog<ZoneChoice> create(ZoneId initial, long clockUnknownCount, PlanViewModel.ClockPrefill clockInitial) {
        Dialog<ZoneChoice> dialog = new Dialog<>();
        dialog.initOwner(CopybotMainUi.STAGE);
        dialog.setTitle(ResourcesEngine.getString("zone.dialog.title"));
        Instant now = Instant.now();
        List<Entry> entries = ZoneLabels.entries(now);
        VBox content = new VBox(8, new Label(ResourcesEngine.getString("zone.dialog.place")));
        ListView<Entry> zones = filterableList(entries, content, 220);
        select(zones, initial);

        ListView<Entry> clock = null;
        CheckBox noDst = new CheckBox(ResourcesEngine.getString("zone.dialog.no-dst"));
        if (clockUnknownCount > 0) {
            List<Entry> clockEntries = new ArrayList<>(entries);
            ZoneId first = clockInitial.zone();
            if (clockEntries.stream().noneMatch(e -> e.zone().equals(first))) {
                clockEntries.addFirst(new Entry(first, ZoneLabels.label(first, now))); // e.g. a fixed offset set before
            }
            content.getChildren().addAll(new Label(ResourcesEngine.getString("zone.dialog.clock-files", clockUnknownCount)),
                    new Label(ResourcesEngine.getString("zone.dialog.clock")));
            clock = filterableList(clockEntries, content, 120);
            content.getChildren().add(noDst);
            select(clock, first);
            noDst.setSelected(clockInitial.noDst());
            ListView<Entry> clockZones = clock;
            boolean[] chosen = {!first.equals(initial)}; // the camera clock follows the place until it is chosen
            boolean[] syncing = {false};
            clock.getSelectionModel().selectedItemProperty().addListener((o, was, entry) -> chosen[0] |= !syncing[0] && entry != null);
            zones.getSelectionModel().selectedItemProperty().addListener((o, was, entry) -> {
                if (entry != null && !chosen[0]) {
                    syncing[0] = true;
                    select(clockZones, entry.zone());
                    syncing[0] = false;
                }
            });
        }
        ListView<Entry> clockList = clock;
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(zones.getSelectionModel().selectedItemProperty().isNull());
        dialog.setResultConverter(button -> {
            Entry place = zones.getSelectionModel().getSelectedItem();
            if (button != ButtonType.OK || place == null) {
                return null;
            }
            Entry clockEntry = clockList == null ? null : clockList.getSelectionModel().getSelectedItem();
            if (clockUnknownCount == 0 || clockEntry == null) {
                return new ZoneChoice(place.zone(), null);
            }
            ZoneId clockZone = clockEntry.zone();
            if (noDst.isSelected()) {
                // the clock was never moved for the summer time: its winter offset all year
                return new ZoneChoice(place.zone(), clockZone.getRules().getStandardOffset(Instant.now()));
            }
            return new ZoneChoice(place.zone(), clockZone.equals(place.zone()) ? null : clockZone);
        });
        return dialog;
    }
}
