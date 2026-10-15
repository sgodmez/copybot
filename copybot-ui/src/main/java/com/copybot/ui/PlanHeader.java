package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.PipelineSummary;
import com.copybot.ui.model.PlanViewModel;
import com.copybot.ui.model.RecentPipelines;
import com.copybot.ui.util.UiPreferences;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.stream.Collectors;

/** The card of the plan view's header: source and output, last run (badges), details (steps, resume mode) on demand. */
final class PlanHeader {

    /** Whether the details of the header are open: kept while the application runs, from one plan view to the next. */
    private static boolean detailsExpanded;

    private static final String CHIP_STYLE = "-fx-background-color: #f3f4f6; -fx-border-color: #d1d5db; "
            + "-fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 2 10 2 10;";
    private static final String MUTED_STYLE = "-fx-text-fill: #6b7280;";

    private final Label sourceLabel;
    private final Label destinationLabel;
    private final Hyperlink detailsLink;
    /** Its first child is the key label, the badges of the last run follow it. */
    private final HBox lastRunBox;
    private final VBox detailsBox;
    private final FlowPane stepsFlow;
    private final Label resumeModeLabel;

    PlanHeader(Label sourceLabel, Label destinationLabel, Hyperlink detailsLink, HBox lastRunBox, VBox detailsBox,
               FlowPane stepsFlow, Label resumeModeLabel) {
        this.sourceLabel = sourceLabel;
        this.destinationLabel = destinationLabel;
        this.detailsLink = detailsLink;
        this.lastRunBox = lastRunBox;
        this.detailsBox = detailsBox;
        this.stepsFlow = stepsFlow;
        this.resumeModeLabel = resumeModeLabel;
    }

    /** @param summary the pipeline read, null while it is read (or when it could not be summed up) */
    void render(PipelineSummary summary, Path pipelinePath) {
        lastRunBox.getChildren().subList(1, lastRunBox.getChildren().size()).clear(); // after the key label
        stepsFlow.getChildren().clear();
        applyDetailsState();
        if (summary == null) {
            sourceLabel.setText("");
            sourceLabel.setTooltip(null);
            destinationLabel.setText("");
            destinationLabel.setTooltip(null);
            resumeModeLabel.setText("");
            return;
        }
        setPath(sourceLabel, "📂 ", summary.sourceText());
        setPath(destinationLabel, "💾 ", summary.destinationText());
        resumeModeLabel.setText(ResourcesEngine.getString("plan.resume-mode",
                PlanViewModel.resumeModeText(summary.resumeMode())));

        RecentPipelines.LastRun lastRun = UiPreferences.recents().entries().stream()
                .filter(e -> e.path().equals(pipelinePath.toAbsolutePath().normalize()))
                .findFirst()
                .map(RecentPipelines.Entry::lastRun) // null when never run
                .orElse(null);
        if (lastRun == null) {
            lastRunBox.getChildren().add(mutedLabel(ResourcesEngine.getString("recent.never-run")));
        } else {
            lastRunBox.getChildren().add(mutedLabel(PlanViewModel.lastRunDate(lastRun)));
            for (PlanViewModel.Badge badge : PlanViewModel.lastRunBadges(lastRun)) {
                lastRunBox.getChildren().add(badgeLabel(badge));
            }
        }

        boolean first = true;
        for (PipelineSummary.Step step : summary.steps()) {
            if (!first) {
                stepsFlow.getChildren().add(new Label("→"));
            }
            first = false;
            stepsFlow.getChildren().add(chip(step));
        }
    }

    /** "Details": opens or closes them. */
    void toggleDetails() {
        detailsExpanded = !detailsExpanded;
        applyDetailsState();
    }

    private void applyDetailsState() {
        PlanController.show(detailsBox, detailsExpanded);
        detailsLink.setText(ResourcesEngine.getString(detailsExpanded ? "plan.details.hide" : "plan.details.show"));
    }

    private static void setPath(Label label, String icon, String value) {
        label.setText(icon + value);
        label.setTooltip(new Tooltip(value));
    }

    private static Label mutedLabel(String text) {
        Label label = new Label(text);
        label.setStyle(MUTED_STYLE);
        return label;
    }

    private static Label badgeLabel(PlanViewModel.Badge badge) {
        String colors = switch (badge.kind()) {
            case SUCCESS -> "-fx-background-color: #dcfce7; -fx-text-fill: #166534;";
            case ERROR -> "-fx-background-color: #fee2e2; -fx-text-fill: #991b1b;";
            case NEUTRAL -> "-fx-background-color: #e5e7eb; -fx-text-fill: #374151;";
        };
        Label label = new Label(badge.text());
        label.setStyle(colors + " -fx-background-radius: 10; -fx-padding: 1 8 1 8; -fx-font-size: 11px;");
        return label;
    }

    /** A step as a chip: its name behind the icon of its section, its settings in a tooltip. */
    private static Label chip(PipelineSummary.Step step) {
        String icon = switch (step.section()) {
            case IN -> "📂";
            case ANALYZE -> "🏷";
            case PROCESS -> "⚙";
            case OUT -> "💾";
        };
        Label chip = new Label(icon + " " + step.name());
        chip.setStyle(CHIP_STYLE);
        String text = step.settings().isEmpty()
                ? ResourcesEngine.getString("plan.chip.no-setting")
                : step.settings().stream().map(s -> s.label() + " : " + s.value()).collect(Collectors.joining("\n"));
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(250));
        tooltip.setShowDuration(Duration.INDEFINITE);
        chip.setTooltip(tooltip);
        return chip;
    }
}
