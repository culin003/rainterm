package com.raindrop.ui;

import com.raindrop.util.ConfigManager;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural tests for the recursive split tree. They drive the layout
 * operations (split / move / collapse) directly rather than through synthetic
 * drag events, which need a real toolkit drag gesture.
 */
class TerminalSplitAreaTest {

    @BeforeAll
    static void startFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // already initialized
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "JavaFX toolkit not available: " + e.getMessage());
        }
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get();
    }

    private static Tab tab(String title) {
        Tab t = new Tab(title);
        t.setContent(new Label(title));
        t.setClosable(true);
        return t;
    }

    @Test
    void testAddTabGoesToSingleGroup() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            area.addTab(a);
            area.addTab(b);
            assertEquals(1, area.groupCountForTest());
            assertEquals(2, area.getTabCount());
            assertEquals(b, area.getSelectedTab());
            assertTrue(area.getAllTabs().contains(a));
            assertTrue(area.getAllTabs().contains(b));
            return null;
        });
    }

    @Test
    void testSplitRightMovesTabIntoNewGroup() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            area.addTab(a);
            area.addTab(b);
            TabGroupPane source = area.activeGroupForTest();
            TabGroupPane newGroup = area.splitGroup(source, Orientation.HORIZONTAL, false);
            assertEquals(2, area.groupCountForTest());

            area.moveTab(a, newGroup, -1);
            assertEquals(2, area.groupCountForTest());
            assertTrue(source.getTabPane().getTabs().contains(b));
            assertFalse(source.getTabPane().getTabs().contains(a));
            assertTrue(newGroup.getTabPane().getTabs().contains(a));
            assertEquals(a, newGroup.getTabPane().getSelectionModel().getSelectedItem());
            assertEquals(newGroup, area.getGroupOfTab(a));
            return null;
        });
    }

    @Test
    void testMoveLastTabOutCollapsesEmptyGroup() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            area.addTab(a);
            TabGroupPane source = area.activeGroupForTest();
            TabGroupPane newGroup = area.splitGroup(source, Orientation.VERTICAL, false);
            area.moveTab(a, newGroup, -1);
            assertEquals(0, source.getTabPane().getTabs().size());
            area.collapseIfEmpty(source);
            assertEquals(1, area.groupCountForTest());
            assertTrue(area.getAllTabs().contains(a));
            return null;
        });
    }

    @Test
    void testCollapseIfEmptyKeepsNonEmptyGroup() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            Tab c = tab("c");
            area.addTab(a);
            area.addTab(b);
            area.addTab(c);
            TabGroupPane source = area.activeGroupForTest();
            TabGroupPane right = area.splitGroup(source, Orientation.HORIZONTAL, false);
            // Move only ONE tab out: the source still holds tabs and must survive.
            area.moveTab(b, right, -1);
            area.collapseIfEmpty(source);
            assertEquals(2, area.groupCountForTest());
            assertTrue(area.getAllTabs().contains(a));
            assertTrue(area.getAllTabs().contains(c));
            assertTrue(area.getAllTabs().contains(b));
            return null;
        });
    }

    @Test
    void testNestedSplitCreatesThreeGroups() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            area.addTab(a);
            area.addTab(b);
            TabGroupPane first = area.activeGroupForTest();
            TabGroupPane right = area.splitGroup(first, Orientation.HORIZONTAL, false);
            area.splitGroup(right, Orientation.VERTICAL, false);
            assertEquals(3, area.groupCountForTest());
            return null;
        });
    }

    @Test
    void testRemoveTabRemovesTabAndKeepsSingleGroup() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            area.addTab(a);
            area.removeTab(a);
            assertEquals(0, area.getTabCount());
            assertEquals(1, area.groupCountForTest());
            assertFalse(area.getAllTabs().contains(a));
            return null;
        });
    }

    @Test
    void testSelectNextCyclesWithinActiveGroup() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            Tab c = tab("c");
            area.addTab(a);
            area.addTab(b);
            area.addTab(c);
            assertEquals(c, area.getSelectedTab());
            area.selectNext(1);
            assertEquals(a, area.getSelectedTab());
            area.selectNext(-1);
            assertEquals(c, area.getSelectedTab());
            return null;
        });
    }

    /**
     * Removing the last tab of a non-root group (as the × close button does)
     * must collapse that group on the next pulse via the tabs-list listener.
     */
    @Test
    void testLastTabRemovedCollapsesNonRootGroupNextPulse() throws Exception {
        AtomicReference<TerminalSplitArea> ref = new AtomicReference<>();
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            area.addTab(a);
            area.addTab(b);
            TabGroupPane source = area.activeGroupForTest();
            TabGroupPane right = area.splitGroup(source, Orientation.HORIZONTAL, false);
            area.moveTab(b, right, -1);
            assertEquals(2, area.groupCountForTest());
            // Simulate the close button: plain removal, no explicit collapse.
            source.getTabPane().getTabs().remove(a);
            ref.set(area);
            return null;
        });
        Integer count = onFx(() -> ref.get().groupCountForTest());
        assertEquals(1, count);
    }

    @Test
    void testMoveTabBetweenGroupsDoesNotLoseTab() throws Exception {
        onFx(() -> {
            TerminalSplitArea area = new TerminalSplitArea();
            Tab a = tab("a");
            Tab b = tab("b");
            Tab c = tab("c");
            area.addTab(a);
            area.addTab(b);
            area.addTab(c);
            TabGroupPane source = area.activeGroupForTest();
            TabGroupPane right = area.splitGroup(source, Orientation.HORIZONTAL, false);
            area.moveTab(b, right, 0);
            assertTrue(source.getTabPane().getTabs().contains(a));
            assertTrue(source.getTabPane().getTabs().contains(c));
            assertEquals(1, right.getTabPane().getTabs().size());
            assertEquals(b, right.getTabPane().getTabs().get(0));
            return null;
        });
    }

    /**
     * The drag-tip banner appears once a single group holds tabs, disappears on
     * the first split, and is permanently suppressed afterwards.
     */
    @Test
    void testDragTipShowsOnceAndDismissesOnSplit() throws Exception {
        ConfigManager.getInstance().set(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED, "false");
        try {
            onFx(() -> {
                TerminalSplitArea area = new TerminalSplitArea();
                area.addTab(tab("a"));
                assertTrue(area.isTipVisibleForTest(), "tip should show for a single group with tabs");

                TabGroupPane source = area.activeGroupForTest();
                area.splitGroup(source, Orientation.HORIZONTAL, false);
                assertFalse(area.isTipVisibleForTest(), "tip should hide after the first split");
                return null;
            });
            assertTrue(ConfigManager.getInstance().getBoolean(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED, false),
                "splitting should persist the dismissal flag");

            onFx(() -> {
                TerminalSplitArea area2 = new TerminalSplitArea();
                area2.addTab(tab("b"));
                assertFalse(area2.isTipVisibleForTest(), "tip should not re-show once dismissed");
                return null;
            });
        } finally {
            ConfigManager.getInstance().remove(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED);
        }
    }

    /** Closing the last tab must not leave the tip hovering over an empty area. */
    @Test
    void testDragTipHidesWhenAllTabsAreClosed() throws Exception {
        ConfigManager.getInstance().set(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED, "false");
        try {
            onFx(() -> {
                TerminalSplitArea area = new TerminalSplitArea();
                Tab a = tab("a");
                area.addTab(a);
                assertTrue(area.isTipVisibleForTest());
                area.removeTab(a);
                assertFalse(area.isTipVisibleForTest(), "tip should hide once the area is empty");
                return null;
            });
        } finally {
            ConfigManager.getInstance().remove(ConfigManager.KEY_TAB_DRAG_TIP_DISMISSED);
        }
    }
}
