package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineDocument;
import com.google.gson.JsonObject;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the form of a run-end action adds to the usual configuration form (spec run-end, editor): its conditions, one
 * box per outcome of the run. The time limit is among its advanced fields ({@link PipelineDocument#advancedFields}).
 */
final class RunEndForms {

    private RunEndForms() {
    }

    /** The success / error / cancelled boxes; the last box checked cannot be unchecked (at least one condition). */
    static Node conditions(PipelineDocument document, JsonObject step) {
        List<String> checked = document.runEndWhen(step);
        List<CheckBox> boxes = new ArrayList<>();
        for (String outcome : PipelineDocument.RUN_END_OUTCOMES) {
            CheckBox box = new CheckBox(ResourcesEngine.getString("editor.run-end.when." + outcome));
            box.setUserData(outcome);
            box.setSelected(checked.contains(outcome));
            boxes.add(box);
        }
        Runnable lockLast = () -> {
            long count = boxes.stream().filter(CheckBox::isSelected).count();
            boxes.forEach(box -> box.setDisable(box.isSelected() && count == 1));
        };
        for (CheckBox box : boxes) {
            box.selectedProperty().addListener((obs, was, selected) -> {
                Set<String> outcomes = new LinkedHashSet<>();
                boxes.stream().filter(CheckBox::isSelected).forEach(b -> outcomes.add((String) b.getUserData()));
                if (!outcomes.isEmpty()) {
                    document.setRunEndWhen(step, outcomes);
                }
                lockLast.run();
            });
        }
        lockLast.run();
        Label help = new Label(ResourcesEngine.getString("editor.run-end.when.help"));
        help.setWrapText(true);
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
        VBox box = new VBox(3, new Label(ResourcesEngine.getString("editor.run-end.when")));
        box.getChildren().addAll(boxes);
        box.getChildren().add(help);
        return box;
    }
}
