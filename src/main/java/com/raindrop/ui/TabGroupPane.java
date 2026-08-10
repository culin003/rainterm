package com.raindrop.ui;

import javafx.scene.control.TabPane;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * A single leaf of the terminal split tree: one {@link TabPane} plus two
 * mouse-transparent overlays — a "dim" overlay for non-active groups and a
 * drop-zone overlay used while a tab is being dragged over the content area.
 *
 * <p>All drag gesture coordination lives in the owning {@link TerminalSplitArea}.
 */
final class TabGroupPane extends StackPane {

    /** Fraction of the content area claimed by each edge drop zone. */
    static final double ZONE_RATIO = 0.22;

    private static final Color NEUTRAL_ZONE = Color.rgb(120, 120, 120, 0.18);
    private static final Color HIGHLIGHT_ZONE = Color.rgb(0, 122, 204, 0.35);

    private final TabPane tabPane = new TabPane();
    private final Region dimOverlay;
    private final Pane dropOverlay = new Pane();
    private final Rectangle[] zones = new Rectangle[4];
    private final TerminalSplitArea owner;

    TabGroupPane(TerminalSplitArea owner) {
        this.owner = owner;
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        dimOverlay = new Region();
        dimOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.45);");
        dimOverlay.setMouseTransparent(true);
        dimOverlay.setVisible(false);

        dropOverlay.setMouseTransparent(true);
        dropOverlay.setVisible(false);
        for (int i = 0; i < zones.length; i++) {
            Rectangle r = new Rectangle();
            r.setArcWidth(8);
            r.setArcHeight(8);
            r.setFill(NEUTRAL_ZONE);
            zones[i] = r;
            dropOverlay.getChildren().add(r);
        }

        getChildren().addAll(tabPane, dimOverlay, dropOverlay);

        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> owner.onGroupPressed(this, e));
        tabPane.addEventFilter(MouseEvent.DRAG_DETECTED, e -> owner.onTabDragDetected(tabPane, e));
        tabPane.setOnDragDone(e -> owner.finishDrag());

        addEventFilter(DragEvent.DRAG_OVER, e -> owner.onDragOver(this, e));
        addEventFilter(DragEvent.DRAG_EXITED, e -> owner.onDragExited(this, e));
        addEventFilter(DragEvent.DRAG_DROPPED, e -> owner.onDragDropped(this, e));
    }

    TabPane getTabPane() {
        return tabPane;
    }

    Pane getDropOverlay() {
        return dropOverlay;
    }

    Rectangle[] getZones() {
        return zones;
    }

    void setActive(boolean active) {
        dimOverlay.setVisible(!active);
    }

    void setZoneColor(int index, boolean highlight) {
        if (index >= 0 && index < zones.length) {
            zones[index].setFill(highlight ? HIGHLIGHT_ZONE : NEUTRAL_ZONE);
        }
    }
}
