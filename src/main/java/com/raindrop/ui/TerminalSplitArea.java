package com.raindrop.ui;

import com.raindrop.storage.ConnectionProfile;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Recursive terminal split container shown in the main window's center pane.
 *
 * <p>The area is a tree: every leaf is a {@link TabGroupPane} (an independent
 * TabPane) and every internal node is a {@link SplitPane} (horizontal for
 * left/right splits, vertical for top/bottom splits). The tree is maintained
 * explicitly in {@link PaneNode}s because {@code SplitPane.getItems()} does not
 * update a child's {@code parent} until the skin lays out — relying on the
 * node graph would be racy within a single drag event.
 *
 * <p>Interaction model (drag-only):
 * <ul>
 *   <li>drag a terminal tab onto a group's content edge → split that group,
 *       moving the tab into the new pane (default) or, with Shift held,
 *       cloning a fresh SSH session into it;</li>
 *   <li>drag any tab onto another group's tab bar → move/reorder the tab;</li>
 *   <li>the pane under the mouse shows four translucent edge drop zones.</li>
 * </ul>
 */
public class TerminalSplitArea extends StackPane {

    private enum DropZone {
        TAB_BAR, TOP, BOTTOM, LEFT, RIGHT, CENTER;

        boolean isEdge() {
            return this == TOP || this == BOTTOM || this == LEFT || this == RIGHT;
        }
    }

    private static final String DRAG_TOKEN = "raindrop-tab-drag";

    private PaneNode root;
    private TabGroupPane activeGroup;
    private TerminalAreaHost host;

    // Drag gesture state (FX thread only).
    private Tab draggingTab;
    private boolean dragging;

    private final HBox dragTip;
    private final Label dragTipText;

    public TerminalSplitArea() {
        TabGroupPane initial = createGroup();
        root = new PaneNode(initial);
        activeGroup = initial;
        dragTipText = new Label();
        dragTip = createDragTip();
        getChildren().addAll(initial, dragTip);
    }

    public void setHost(TerminalAreaHost host) {
        this.host = host;
    }

    // ------------------------------------------------------------ drag tip

    /** One-time, dismissible banner teaching that tabs can be dragged to split. */
    private HBox createDragTip() {
        dragTipText.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 12;");
        Label close = new Label("\u2715");
        close.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 12; -fx-cursor: hand; -fx-padding: 0 0 0 10;");
        close.setOnMouseClicked(e -> dismissDragTip());
        HBox box = new HBox(6, dragTipText, close);
        box.setAlignment(Pos.CENTER);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setStyle("-fx-background-color: rgba(0, 122, 204, 0.92);"
            + " -fx-background-radius: 14; -fx-padding: 6 14;");
        box.setVisible(false);
        StackPane.setAlignment(box, Pos.TOP_CENTER);
        StackPane.setMargin(box, new Insets(40, 0, 0, 0));
        return box;
    }

    /** Show the tip once a single group holds tabs, unless the user already split or dismissed it. */
    private void maybeShowDragTip() {
        if (dragTip.isVisible() || isDragTipDismissed()) return;
        if (getTabCount() > 0 && collectGroups().size() == 1) {
            dragTipText.setText(I18nManager.t("terminal.drag_tip"));
            dragTip.setVisible(true);
        }
    }

    private void dismissDragTip() {
        dragTip.setVisible(false);
        if (!isDragTipDismissed()) {
            ConfigManager.getInstance().set(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED, "true");
        }
    }

    private boolean isDragTipDismissed() {
        return ConfigManager.getInstance().getBoolean(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED, false);
    }

    // ------------------------------------------------------------------ API

    public TabPane getActiveTabPane() {
        return activeGroup == null ? null : activeGroup.getTabPane();
    }

    public Tab getSelectedTab() {
        return activeGroup == null ? null : activeGroup.getTabPane().getSelectionModel().getSelectedItem();
    }

    public int getTabCount() {
        return collectGroups().stream().mapToInt(g -> g.getTabPane().getTabs().size()).sum();
    }

    public List<Tab> getAllTabs() {
        List<Tab> tabs = new ArrayList<>();
        for (TabGroupPane g : collectGroups()) {
            tabs.addAll(g.getTabPane().getTabs());
        }
        return tabs;
    }

    public void addTab(Tab tab) {
        TabGroupPane target = activeGroup != null ? activeGroup : firstGroupOrCreate();
        target.getTabPane().getTabs().add(tab);
        target.getTabPane().getSelectionModel().select(tab);
        activate(target);
    }

    public void addTabToGroup(Tab tab, TabGroupPane group) {
        if (group == null) {
            addTab(tab);
            return;
        }
        group.getTabPane().getTabs().add(tab);
        group.getTabPane().getSelectionModel().select(tab);
        activate(group);
    }

    public void removeTab(Tab tab) {
        TabGroupPane g = findGroup(tab);
        if (g == null) return;
        g.getTabPane().getTabs().remove(tab);
        collapseIfEmpty(g);
        if (activeGroup == g && g.getTabPane().getTabs().isEmpty()) {
            TabGroupPane fallback = collectGroups().stream().findFirst().orElse(null);
            if (fallback != null) activate(fallback);
        }
    }

    public void selectTab(Tab tab) {
        TabGroupPane g = findGroup(tab);
        if (g == null) return;
        g.getTabPane().getSelectionModel().select(tab);
        activate(g);
    }

    public void selectNext(int delta) {
        TabPane pane = activeGroup == null ? null : activeGroup.getTabPane();
        if (pane == null) return;
        int size = pane.getTabs().size();
        if (size <= 1) return;
        int idx = pane.getSelectionModel().getSelectedIndex();
        int next = ((idx + delta) % size + size) % size;
        pane.getSelectionModel().select(next);
    }

    public void selectIndex(int index) {
        TabPane pane = activeGroup == null ? null : activeGroup.getTabPane();
        if (pane != null && index < pane.getTabs().size()) {
            pane.getSelectionModel().select(index);
        }
    }

    public TabGroupPane getGroupOfTab(Tab tab) {
        return findGroup(tab);
    }

    /** Test hook: the currently active group. */
    TabGroupPane activeGroupForTest() {
        return activeGroup;
    }

    /** Test hook: whether the drag-tip banner is currently visible. */
    boolean isTipVisibleForTest() {
        return dragTip.isVisible();
    }

    /** Test hook: make a group active (mirrors the clone drop's activate). */
    void activateForTest(TabGroupPane group) {
        activate(group);
    }

    /** Test hook: every leaf group currently in the tree. */
    List<TabGroupPane> groupsForTest() {
        return collectGroups();
    }

    /** Test hook: number of leaf groups currently in the tree. */
    int groupCountForTest() {
        return collectGroups().size();
    }

    /** Remove every tab (firing each tab's onClosed) and reset to a single empty group. */
    public void clearAllTabs() {
        for (TabGroupPane g : collectGroups()) {
            g.getTabPane().getTabs().clear();
        }
        TabGroupPane initial = createGroup();
        root = new PaneNode(initial);
        activeGroup = initial;
        draggingTab = null;
        dragTip.setVisible(false);
        getChildren().setAll(initial, dragTip);
    }

    // ------------------------------------------------------- drag-gesture UI

    void onTabDragDetected(TabPane pane, MouseEvent e) {
        if (dragging) return;
        if (!(e.getTarget() instanceof Node target)) return;
        Tab tab = tabUnder(target, pane);
        if (tab == null || !pane.getTabs().contains(tab)) return;
        startDrag(pane, tab, e.isShiftDown());
        e.consume();
    }

    /** Walk up from a mouse target to find the header that owns it (headers store their Tab in {@code properties}). */
    private static Tab tabUnder(Node target, TabPane pane) {
        for (Node n = target; n != null && n != pane; n = n.getParent()) {
            Object o = n.getProperties().get(Tab.class);
            if (o instanceof Tab t) return t;
        }
        return null;
    }

    private void startDrag(TabPane pane, Tab tab, boolean copyMode) {
        dragging = true;
        draggingTab = tab;
        TransferMode mode = copyMode ? TransferMode.COPY : TransferMode.MOVE;
        Dragboard db = pane.startDragAndDrop(mode);
        ClipboardContent content = new ClipboardContent();
        content.putString(DRAG_TOKEN);
        db.setContent(content);

        Label ghost = new Label(tab.getText());
        ghost.setStyle("-fx-background-color: #333333; -fx-text-fill: #e8e8e8;"
            + " -fx-padding: 4 10; -fx-border-color: rgba(255,255,255,0.4);"
            + " -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;");
        ghost.applyCss();
        ghost.layout();
        WritableImage img = ghost.snapshot(null, null);
        db.setDragView(img, 12, 12);
    }

    void onDragOver(TabGroupPane group, DragEvent e) {
        if (draggingTab == null) return;
        DropZone zone = resolveZone(group, e);
        boolean terminal = host != null && host.isTerminalTab(draggingTab);
        boolean allowed = zone == DropZone.TAB_BAR
            || (terminal && zone.isEdge());
        hideAllDropZones();
        if (allowed) {
            e.acceptTransferModes(TransferMode.MOVE, TransferMode.COPY);
            if (zone.isEdge()) updateDropZones(group, zone);
        } else {
            e.acceptTransferModes();
        }
        e.consume();
    }

    void onDragExited(TabGroupPane group, DragEvent e) {
        group.getDropOverlay().setVisible(false);
    }

    void onDragDropped(TabGroupPane group, DragEvent e) {
        if (draggingTab == null) {
            e.setDropCompleted(false);
            e.consume();
            return;
        }
        DropZone zone = resolveZone(group, e);
        boolean clone = e.getTransferMode() == TransferMode.COPY;
        boolean completed = false;
        try {
            if (zone == DropZone.TAB_BAR && !clone) {
                completed = dropOnTabBar(group, e);
            } else if (zone.isEdge()) {
                completed = dropOnEdge(group, zone, clone);
            }
        } finally {
            e.setDropCompleted(completed);
            finishDrag();
        }
        e.consume();
    }

    private boolean dropOnTabBar(TabGroupPane group, DragEvent e) {
        TabGroupPane source = findGroup(draggingTab);
        int index = indexForDrop(group, e);
        if (source == group) {
            reorderTab(draggingTab, group.getTabPane(), index);
        } else {
            moveTab(draggingTab, group, index);
            collapseIfEmpty(source);
        }
        activate(group);
        return true;
    }

    private boolean dropOnEdge(TabGroupPane group, DropZone zone, boolean clone) {
        if (host == null || !host.isTerminalTab(draggingTab)) return false;
        Orientation orientation = (zone == DropZone.LEFT || zone == DropZone.RIGHT)
            ? Orientation.HORIZONTAL : Orientation.VERTICAL;
        boolean before = zone == DropZone.LEFT || zone == DropZone.TOP;
        TabGroupPane newGroup = splitGroup(group, orientation, before);
        if (newGroup == null) return false;
        if (clone) {
            ConnectionProfile profile = host.getTabProfile(draggingTab);
            if (profile == null) {
                collapseIfEmpty(newGroup);
                return true;
            }
            activate(newGroup);
            host.openTab(profile);
        } else {
            TabGroupPane source = findGroup(draggingTab);
            moveTab(draggingTab, newGroup, -1);
            activate(newGroup);
            collapseIfEmpty(source);
        }
        return true;
    }

    /** Tab movement must not fire onClosed (which would tear down the session). */
    void moveTab(Tab tab, TabGroupPane target, int index) {
        if (target == null) return;
        TabGroupPane source = findGroup(tab);
        EventHandler<Event> closed = tab.getOnClosed();
        tab.setOnClosed(null);
        if (source != null) source.getTabPane().getTabs().remove(tab);
        ObservableList<Tab> tabs = target.getTabPane().getTabs();
        if (index < 0 || index > tabs.size()) index = tabs.size();
        tabs.add(index, tab);
        tab.setOnClosed(closed);
        target.getTabPane().getSelectionModel().select(tab);
    }

    private void reorderTab(Tab tab, TabPane pane, int to) {
        EventHandler<Event> closed = tab.getOnClosed();
        tab.setOnClosed(null);
        int from = pane.getTabs().indexOf(tab);
        if (from >= 0) pane.getTabs().remove(from);
        if (to < 0 || to > pane.getTabs().size()) to = pane.getTabs().size();
        pane.getTabs().add(to, tab);
        tab.setOnClosed(closed);
        pane.getSelectionModel().select(tab);
    }

    void onGroupPressed(TabGroupPane group, MouseEvent e) {
        activate(group);
        if (dragging) {
            // Self-heal: if DRAG_DONE was ever lost (e.g. the source pane was
            // detached mid-gesture) the flag would block every future drag.
            dragging = false;
            draggingTab = null;
        }
    }

    void finishDrag() {
        draggingTab = null;
        dragging = false;
        hideAllDropZones();
    }

    // ------------------------------------------------------------- geometry

    private DropZone resolveZone(TabGroupPane group, DragEvent e) {
        Node content = group.getTabPane().lookup(".tab-content-area");
        if (content == null) return DropZone.TAB_BAR;
        Bounds cb = content.localToScene(content.getBoundsInLocal());
        double sx = e.getSceneX();
        double sy = e.getSceneY();
        if (sx < cb.getMinX() || sx > cb.getMaxX() || sy < cb.getMinY() || sy > cb.getMaxY()) {
            return DropZone.TAB_BAR;
        }
        double rx = (sx - cb.getMinX()) / cb.getWidth();
        double ry = (sy - cb.getMinY()) / cb.getHeight();
        if (ry < TabGroupPane.ZONE_RATIO) return DropZone.TOP;
        if (ry > 1 - TabGroupPane.ZONE_RATIO) return DropZone.BOTTOM;
        if (rx < TabGroupPane.ZONE_RATIO) return DropZone.LEFT;
        if (rx > 1 - TabGroupPane.ZONE_RATIO) return DropZone.RIGHT;
        return DropZone.CENTER;
    }

    private void updateDropZones(TabGroupPane group, DropZone highlight) {
        Node content = group.getTabPane().lookup(".tab-content-area");
        Pane overlay = group.getDropOverlay();
        if (content == null) {
            overlay.setVisible(false);
            return;
        }
        Bounds cb = content.localToScene(content.getBoundsInLocal());
        Bounds gb = group.localToScene(group.getBoundsInLocal());
        double ox = cb.getMinX() - gb.getMinX();
        double oy = cb.getMinY() - gb.getMinY();
        double w = cb.getWidth();
        double h = cb.getHeight();
        double edge = TabGroupPane.ZONE_RATIO;
        double topH = h * edge;
        double sideW = w * edge;
        double midH = h - 2 * topH;

        Rectangle[] zones = group.getZones();
        position(zones[0], 0, ox, oy, w, topH, highlight == DropZone.TOP, group);
        position(zones[1], 1, ox, oy + h - topH, w, topH, highlight == DropZone.BOTTOM, group);
        position(zones[2], 2, ox, oy + topH, sideW, midH, highlight == DropZone.LEFT, group);
        position(zones[3], 3, ox + w - sideW, oy + topH, sideW, midH, highlight == DropZone.RIGHT, group);
        overlay.setVisible(true);
    }

    private void position(Rectangle r, int index, double x, double y, double w, double h, boolean highlight, TabGroupPane group) {
        r.setX(x);
        r.setY(y);
        r.setWidth(w);
        r.setHeight(h);
        group.setZoneColor(index, highlight);
    }

    private int indexForDrop(TabGroupPane group, DragEvent e) {
        TabPane pane = group.getTabPane();
        int count = pane.getTabs().size();
        if (count == 0) return 0;
        List<Node> headers = new ArrayList<>(pane.lookupAll(".tab"));
        headers.sort(Comparator.comparingDouble(h -> h.localToScene(h.getBoundsInLocal()).getMinX()));
        double sx = e.getSceneX();
        int idx = headers.size();
        for (int i = 0; i < headers.size(); i++) {
            Bounds b = headers.get(i).localToScene(headers.get(i).getBoundsInLocal());
            if (sx < b.getMaxX()) {
                idx = i;
                break;
            }
        }
        return Math.min(idx, count);
    }

    // ------------------------------------------------------------- tree ops

    private void hideAllDropZones() {
        for (TabGroupPane g : collectGroups()) {
            g.getDropOverlay().setVisible(false);
        }
    }

    TabGroupPane splitGroup(TabGroupPane target, Orientation orientation, boolean before) {
        PaneNode targetNode = findNode(target);
        if (targetNode == null) return null;

        TabGroupPane newGroupFx = createGroup();
        PaneNode newGroupNode = new PaneNode(newGroupFx);
        SplitPane splitFx = new SplitPane();
        splitFx.setOrientation(orientation);
        PaneNode splitNode = new PaneNode(splitFx);

        if (before) {
            splitNode.children.add(newGroupNode);
            splitNode.children.add(targetNode);
            splitFx.getItems().addAll(newGroupFx, target);
        } else {
            splitNode.children.add(targetNode);
            splitNode.children.add(newGroupNode);
            splitFx.getItems().addAll(target, newGroupFx);
        }
        PaneNode oldParent = targetNode.parent;
        targetNode.parent = splitNode;
        newGroupNode.parent = splitNode;

        if (oldParent == null) {
            root = splitNode;
            setRootFx(splitFx);
        } else {
            int idx = oldParent.children.indexOf(targetNode);
            oldParent.children.set(idx, splitNode);
            splitNode.parent = oldParent;
            ((SplitPane) oldParent.fxNode).getItems().set(idx, splitFx);
        }
        dismissDragTip();
        return newGroupFx;
    }

    void collapseIfEmpty(TabGroupPane group) {
        if (group == null || !group.getTabPane().getTabs().isEmpty()) return;
        PaneNode node = findNode(group);
        if (node == null || node.parent == null) return;
        PaneNode parent = node.parent;
        int idx = parent.children.indexOf(node);
        if (idx < 0) return;
        parent.children.remove(idx);
        ((SplitPane) parent.fxNode).getItems().remove(idx);
        if (parent.children.size() == 1) {
            PaneNode remaining = parent.children.get(0);
            remaining.parent = parent.parent;
            if (parent.parent == null) {
                root = remaining;
                setRootFx(remaining.fxNode);
            } else {
                int pi = parent.parent.children.indexOf(parent);
                parent.parent.children.set(pi, remaining);
                ((SplitPane) parent.parent.fxNode).getItems().set(pi, remaining.fxNode);
            }
        }
        if (activeGroup == group) {
            TabGroupPane fallback = collectGroups().stream().findFirst().orElse(null);
            if (fallback != null) activate(fallback);
        }
    }

    private void setRootFx(Node node) {
        getChildren().setAll(node, dragTip);
    }

    private void activate(TabGroupPane group) {
        if (group == null) return;
        activeGroup = group;
        for (TabGroupPane g : collectGroups()) {
            g.setActive(g == group);
        }
    }

    private TabGroupPane findGroup(Tab tab) {
        if (tab == null) return null;
        for (TabGroupPane g : collectGroups()) {
            if (g.getTabPane().getTabs().contains(tab)) return g;
        }
        return null;
    }

    private PaneNode findNode(TabGroupPane group) {
        return root == null ? null : findNode(root, group);
    }

    private static PaneNode findNode(PaneNode node, TabGroupPane group) {
        if (node.fxNode == group) return node;
        for (PaneNode child : node.children) {
            PaneNode hit = findNode(child, group);
            if (hit != null) return hit;
        }
        return null;
    }

    private TabGroupPane firstGroupOrCreate() {
        TabGroupPane g = collectGroups().stream().findFirst().orElse(null);
        if (g == null) {
            g = createGroup();
            root = new PaneNode(g);
            setRootFx(g);
        }
        return g;
    }

    /** Build a group and auto-collapse it next pulse whenever its last tab is removed (incl. via the × button). */
    private TabGroupPane createGroup() {
        TabGroupPane group = new TabGroupPane(this);
        group.getTabPane().getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                if (change.wasAdded()) maybeShowDragTip();
                if (change.wasRemoved() && getTabCount() == 0) dragTip.setVisible(false);
            }
            if (group.getTabPane().getTabs().isEmpty()) {
                Platform.runLater(() -> collapseIfEmpty(group));
            }
        });
        return group;
    }

    private List<TabGroupPane> collectGroups() {
        List<TabGroupPane> out = new ArrayList<>();
        if (root != null) collectGroups(root, out);
        return out;
    }

    private static void collectGroups(PaneNode node, List<TabGroupPane> out) {
        if (node.fxNode instanceof TabGroupPane g) {
            out.add(g);
        } else {
            for (PaneNode child : node.children) {
                collectGroups(child, out);
            }
        }
    }

    /** Explicit node of the split tree. {@code children} is non-empty only for split nodes. */
    private static final class PaneNode {
        final Node fxNode;
        PaneNode parent;
        final List<PaneNode> children = new ArrayList<>();

        PaneNode(Node fxNode) {
            this.fxNode = fxNode;
        }
    }
}
