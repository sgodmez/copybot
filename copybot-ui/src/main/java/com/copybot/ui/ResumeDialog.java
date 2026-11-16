package com.copybot.ui;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ItemKey;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.Choosers;
import com.copybot.ui.util.PopinUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** The resume point dialog of the plan view ("change…"). */
final class ResumeDialog {

    /** The date of a resume key in the "from a file" list (like the Date column). */
    private static final DateTimeFormatter RESUME_KEY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** The file last chosen on disk in the resume dialog: the next chooser opens in its folder. */
    private static Path lastResumeFile;

    /** A choice of the resume dialog: a point, or null for the automatic one. */
    record ResumeChoice(ResumePoint point) {
    }

    private ResumeDialog() {
    }

    /**
     * Everything / from a date / from a file of the plan; with {@code withAuto} (before a prepared plan, spec
     * manual-point §3) also "Automatic", and a file chosen on disk when no file is listed yet.
     *
     * @param items       the rows of the view: their keys are the files offered
     * @param chosenPoint the point chosen for the next preparation, shown selected with {@code withAuto}
     */
    static Dialog<ResumeChoice> create(List<WorkItemExecution> items, ResumePoint chosenPoint, boolean withAuto) {
        Dialog<ResumeChoice> dialog = new Dialog<>();
        dialog.initOwner(CopybotMainUi.STAGE);
        dialog.setTitle(ResourcesEngine.getString("resume.dialog.title"));
        ToggleGroup group = new ToggleGroup();
        RadioButton auto = new RadioButton(ResourcesEngine.getString("resume.dialog.auto"));
        RadioButton all = new RadioButton(ResourcesEngine.getString("resume.dialog.all"));
        RadioButton fromDate = new RadioButton(ResourcesEngine.getString("resume.dialog.date"));
        RadioButton fromFile = new RadioButton(ResourcesEngine.getString("resume.dialog.file"));
        List.of(auto, all, fromDate, fromFile).forEach(b -> b.setToggleGroup(group));
        ResumePoint current = withAuto ? chosenPoint : null;
        (withAuto && current == null ? auto : all).setSelected(true);
        DatePicker date = new DatePicker(LocalDate.now());
        // the keys themselves, "name (date)": two files of the same name (counter rollover) stay distinct
        ComboBox<ItemKey> file = new ComboBox<>();
        file.setConverter(new StringConverter<>() {
            @Override
            public String toString(ItemKey key) {
                return key == null ? "" : key.name() + " (" + RESUME_KEY_DATE.format(key.date().atZone(ZoneId.systemDefault())) + ")";
            }

            @Override
            public ItemKey fromString(String s) {
                return null; // not editable
            }
        });
        // the listed files (a prepared plan, a stopped preparation): their keys, the date known at the listing
        items.stream()
                .map(item -> item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem())))
                .flatMap(Optional::stream)
                .sorted()
                .forEach(file.getItems()::add);
        if (!file.getItems().isEmpty()) {
            file.setValue(file.getItems().getFirst());
        }
        if (current != null && current.kind() == ResumePoint.Kind.FROM && current.key() != null) {
            if (current.key().name().isEmpty()) {
                fromDate.setSelected(true);
                date.setValue(LocalDate.ofInstant(current.key().date(), ZoneId.systemDefault()));
            } else {
                fromFile.setSelected(true);
                if (!file.getItems().contains(current.key())) {
                    file.getItems().addFirst(current.key());
                }
                file.setValue(current.key());
            }
        }
        // nothing listed yet: a file chosen on disk gives the key the listing will give it
        Button chooseFile = new Button(ResourcesEngine.getString("resume.dialog.choose-file"));
        BooleanProperty reading = new SimpleBooleanProperty(); // the chooser's folder, then the chosen file
        chooseFile.setOnAction(e -> {
            javafx.stage.Window window = dialog.getDialogPane().getScene().getWindow();
            reading.set(true);
            Choosers.inFolderOf(null, resumeFileFolder(items, file.getValue()), folder -> {
                reading.set(false);
                javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
                chooser.setInitialDirectory(folder);
                java.io.File chosen = chooser.showOpenDialog(window);
                if (chosen == null) {
                    return;
                }
                lastResumeFile = chosen.toPath();
                reading.set(true);
                Background.run(() -> Plan.fromFile(chosen.toPath()).key(), // its date is read from the file
                        key -> {
                            reading.set(false);
                            file.getItems().addFirst(key);
                            file.setValue(key);
                        },
                        failure -> {
                            reading.set(false);
                            PopinUtil.showError(window, failure);
                        });
            });
        });
        date.disableProperty().bind(fromDate.selectedProperty().not());
        file.disableProperty().bind(fromFile.selectedProperty().not());
        chooseFile.disableProperty().bind(fromFile.selectedProperty().not().or(reading));
        VBox content = new VBox(8);
        if (withAuto) {
            content.getChildren().add(auto);
        }
        content.getChildren().addAll(all, new HBox(8, fromDate, date), new HBox(8, fromFile, file, chooseFile));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(fromFile.selectedProperty().and(file.valueProperty().isNull())
                .or(fromDate.selectedProperty().and(date.valueProperty().isNull())));
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            if (auto.isSelected()) {
                return new ResumeChoice(null);
            }
            if (fromDate.isSelected()) {
                return new ResumeChoice(Plan.fromDate(date.getValue()));
            }
            if (fromFile.isSelected()) {
                return new ResumeChoice(ResumePoint.from(file.getValue()));
            }
            return new ResumeChoice(ResumePoint.all());
        });
        return dialog;
    }

    /** The listed file of this key, else the file chosen last; null when none. */
    private static String resumeFileFolder(List<WorkItemExecution> items, ItemKey key) {
        Optional<Path> listed = key == null ? Optional.empty() : items.stream()
                .filter(item -> item.getResumeKey().or(() -> ItemKey.of(item.getWorkItem())).filter(key::equals).isPresent())
                .map(WorkItemExecution::getListedPath)
                .flatMap(Optional::stream)
                .findFirst();
        return listed.or(() -> Optional.ofNullable(lastResumeFile)).map(Path::toString).orElse(null);
    }
}
