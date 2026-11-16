package com.copybot.ui;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PlanViewModel;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** The planned processing of analysed rows of the plan view, read only (the magnifier, "Planned processing…"). */
final class ItemDetailDialog {

    private ItemDetailDialog() {
    }

    /**
     * Shows the planned processing of these analysed items, from the clicked one when it is among them: with several,
     * a counter and previous / next buttons go through them.
     *
     * @param shown the plan the items belong to (prepared, or being prepared)
     */
    static void show(Plan shown, List<WorkItemExecution> items, WorkItemExecution clicked) {
        TextArea text = new TextArea();
        text.setEditable(false);
        text.setWrapText(false);
        text.setStyle("-fx-font-family: 'Consolas', 'monospace';");
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(CopybotMainUi.STAGE);
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(950, 560); // long target paths fit without scrolling
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        int[] index = {PlanViewModel.detailStart(items, clicked)};
        Button previous = new Button("◀");
        Button next = new Button("▶");
        Label position = new Label();
        Runnable display = () -> {
            WorkItemExecution item = items.get(index[0]);
            text.setText(shown.detailOf(item).map(detail -> PlanViewModel.detailText(item, detail)).orElse(""));
            dialog.setTitle(ResourcesEngine.getString("plan.detail.title", item.getWorkItem().getNameDisplay()));
            position.setText(ResourcesEngine.getString("plan.detail.position", index[0] + 1, items.size()));
            previous.setDisable(index[0] == 0);
            next.setDisable(index[0] == items.size() - 1);
        };
        previous.setOnAction(e -> {
            index[0]--;
            display.run();
        });
        next.setOnAction(e -> {
            index[0]++;
            display.run();
        });
        display.run();
        if (items.size() > 1) {
            HBox navigation = new HBox(8.0, previous, position, next);
            navigation.setAlignment(Pos.CENTER_LEFT);
            VBox content = new VBox(8.0, navigation, text);
            VBox.setVgrow(text, Priority.ALWAYS);
            dialog.getDialogPane().setContent(content);
        } else {
            dialog.getDialogPane().setContent(text);
        }
        dialog.show();
    }
}
