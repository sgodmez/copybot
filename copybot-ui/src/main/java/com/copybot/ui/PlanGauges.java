package com.copybot.ui;

import com.copybot.engine.pipeline.PipelineState;
import com.copybot.engine.resources.DiskInventory;
import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.DiskRates;
import com.copybot.ui.model.ResourceGauges;
import com.copybot.ui.util.Background;
import com.copybot.ui.util.SystemLoad;
import com.copybot.ui.util.UiPreferences;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The resources of the plan view, live while a plan is prepared or run: on the line of the progress bar, or in a
 * side panel (a preference).
 */
final class PlanGauges {

    private static final System.Logger LOG = System.getLogger(PlanGauges.class.getName());

    /** On the line of the progress bar, the gauges past these are summed up in "+N", their detail in a tooltip. */
    private static final int INLINE_GAUGES = 4;
    private static final double INLINE_BAR = 60;
    private static final double PANEL_BAR = 184;

    private final HBox inlineGauges;
    private final VBox resourcesPanel;
    /** A node of the view: out of its scene, the view was replaced. */
    private final Node viewNode;
    /** The last state notified for the current operation: its registry gives the resources in use. */
    private final Supplier<PipelineState> liveState;
    /** The volumes of the machine, to name the disks; read once, off the JavaFX thread. */
    private List<DiskInventory.Volume> volumes = List.of();
    private boolean volumesAsked;
    /** The volumes could not be read: asked again when the next operation starts. */
    private boolean volumesFailed;
    /** A plan is prepared or run: the gauges are live. */
    private boolean resourcesLive;
    /**
     * Once a second while the view is shown: the CPU load moves between two notifications, and the place of the
     * resources (a preference) may have changed meanwhile.
     */
    private Timeline resourcesTimer;
    /** The gauges are kept from one second to the next (a tooltip being read stays open), by their label. */
    private final Map<String, GaugeView> inlineViews = new LinkedHashMap<>();
    private final Map<String, GaugeView> panelViews = new LinkedHashMap<>();
    private final Label moreGauges = new Label();
    private final Tooltip moreGaugesTip = new Tooltip();
    private Label panelTitle;
    private Label panelIdle;
    /** The throughput of the disks, from the bytes counted once a second. */
    private final DiskRates diskRates = new DiskRates();

    PlanGauges(HBox inlineGauges, VBox resourcesPanel, Node viewNode, Supplier<PipelineState> liveState) {
        this.inlineGauges = inlineGauges;
        this.resourcesPanel = resourcesPanel;
        this.viewNode = viewNode;
        this.liveState = liveState;
    }

    /** @param live a plan is prepared or run */
    void refresh(boolean live) {
        if (live && !resourcesLive && volumesFailed) {
            volumesFailed = false; // a new operation: the volumes are asked again
            volumesAsked = false;
        }
        resourcesLive = live;
        if (live && !volumesAsked) {
            volumesAsked = true;
            Background.run(DiskInventory::volumes, // a share may answer slowly
                    read -> {
                        volumes = read;
                        render();
                    },
                    failure -> {
                        // the gauges go on without the names of the disks
                        LOG.log(System.Logger.Level.WARNING, "the volumes of the machine cannot be read", failure);
                        volumesFailed = true;
                    });
        }
        if (resourcesTimer == null) {
            resourcesTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> render()));
            resourcesTimer.setCycleCount(Animation.INDEFINITE);
            resourcesTimer.play();
        }
        render();
    }

    private void render() {
        if (viewNode.getScene() == null) {
            resourcesTimer.stop(); // the view was replaced
            return;
        }
        boolean panel = UiPreferences.resourcesLayout() == UiPreferences.ResourcesLayout.SIDE_PANEL;
        PlanController.show(resourcesPanel, panel);
        PlanController.show(inlineGauges, !panel && resourcesLive);
        if (panelTitle == null) {
            panelTitle = new Label(ResourcesEngine.getString("plan.resources"));
            panelTitle.setStyle("-fx-font-weight: bold;");
            panelIdle = new Label(ResourcesEngine.getString("plan.resources.idle"));
            panelIdle.setWrapText(true);
            panelIdle.setStyle("-fx-text-fill: #6b7280;");
            moreGauges.setStyle("-fx-text-fill: #6b7280;");
            Tooltip.install(moreGauges, moreGaugesTip);
        }
        if (!resourcesLive) {
            if (panel) {
                setChildren(resourcesPanel, List.of(panelTitle, panelIdle));
            }
            return;
        }
        PipelineState state = liveState.get();
        if (state != null) {
            diskRates.sample(System.nanoTime(), state.getDiskTraffic());
        }
        List<ResourceGauges.Gauge> gauges = ResourceGauges.of(state == null ? List.of() : state.getResourceSnapshot(),
                SystemLoad.machine(), SystemLoad.process(), volumes, diskRates.rates());
        if (panel) {
            List<Node> nodes = new ArrayList<>(List.of(panelTitle));
            nodes.addAll(views(panelViews, gauges, false));
            setChildren(resourcesPanel, nodes);
        } else {
            List<ResourceGauges.Gauge> shown = gauges.subList(0, Math.min(INLINE_GAUGES, gauges.size()));
            List<Node> nodes = new ArrayList<>(views(inlineViews, shown, true));
            List<ResourceGauges.Gauge> rest = gauges.subList(shown.size(), gauges.size());
            if (!rest.isEmpty()) {
                moreGauges.setText(ResourcesEngine.getString("plan.resources.more", rest.size()));
                moreGaugesTip.setText(rest.stream().map(g -> g.label() + " : " + g.text())
                        .collect(Collectors.joining("\n")));
                nodes.add(moreGauges);
            }
            setChildren(inlineGauges, nodes);
        }
    }

    /** The views of the gauges, the ones already shown updated in place. */
    private static List<Node> views(Map<String, GaugeView> views, List<ResourceGauges.Gauge> gauges, boolean inline) {
        List<Node> nodes = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (ResourceGauges.Gauge gauge : gauges) {
            GaugeView view = views.computeIfAbsent(gauge.label(), l -> new GaugeView(inline));
            view.show(gauge);
            nodes.add(view.root);
            labels.add(gauge.label());
        }
        views.keySet().retainAll(labels);
        return nodes;
    }

    /** Replaces the children only when they change: the same nodes keep their tooltip and their hover. */
    private static void setChildren(Pane pane, List<Node> nodes) {
        if (!pane.getChildren().equals(nodes)) {
            pane.getChildren().setAll(nodes);
        }
    }

    /**
     * One gauge: its name, a bar (orange when the resource is full and actions wait for it; for the CPU the load of
     * the machine in light blue and, inside it, the part of Copybot in dark blue) and its value; all of it in the
     * tooltip, with what the gauge counts. Compact on the line of the progress bar, one under the other in the panel.
     */
    private static final class GaugeView {
        private final boolean inline;
        private final double width;
        private final Label name = new Label();
        private final Label value = new Label();
        private final Region fill = layer();
        private final Region part = layer();
        private final Tooltip tip = new Tooltip();
        private final Pane root;

        GaugeView(boolean inline) {
            this.inline = inline;
            this.width = inline ? INLINE_BAR : PANEL_BAR;
            Region track = layer();
            size(track, 1);
            track.setStyle("-fx-background-color: #e5e7eb; -fx-background-radius: 3;");
            part.setStyle("-fx-background-color: #1d4ed8; -fx-background-radius: 3;");
            StackPane bar = new StackPane(track, fill, part);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setMinWidth(width);
            bar.setMaxWidth(width);
            value.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
            if (inline) {
                HBox line = new HBox(5, name, bar, value);
                line.setAlignment(Pos.CENTER_LEFT);
                root = line;
            } else {
                name.setWrapText(true);
                value.setWrapText(true);
                root = new VBox(2, name, bar, value);
            }
            tip.setShowDelay(Duration.millis(300));
            tip.setWrapText(true); // the explanation of what the gauge counts is a few sentences
            tip.setMaxWidth(420);
            Tooltip.install(root, tip);
        }

        void show(ResourceGauges.Gauge gauge) {
            name.setText(inline ? gauge.shortLabel() : gauge.label());
            value.setText(inline ? gauge.shortText() : gauge.text());
            boolean split = gauge.part() >= 0;
            String color = gauge.saturated() ? "#d97706" : split ? "#93c5fd" : "#3b82f6";
            fill.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3;");
            size(fill, Math.max(gauge.fraction(), 0));
            part.setVisible(split);
            size(part, Math.max(gauge.part(), 0));
            tip.setText(gauge.label() + "\n" + gauge.text() + (gauge.tip().isEmpty() ? "" : "\n\n" + gauge.tip()));
        }

        private void size(Region layer, double fraction) {
            double w = Math.round(width * Math.min(fraction, 1));
            layer.setMinSize(w, 10);
            layer.setPrefSize(w, 10);
            layer.setMaxSize(w, 10);
        }

        private static Region layer() {
            return new Region();
        }
    }
}
