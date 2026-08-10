package com.raindrop.ui;

import com.raindrop.storage.ConnectionProfile;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress test that replays the full drag/drop operation mix on a scene that is
 * actually shown, so the {@code SplitPane} skin performs its own item
 * reconciliation between every step. This catches tree corruption that pure
 * off-scene structural tests cannot — the symptom users hit as tabs
 * disappearing (or crashes) after repeated continuous dragging.
 *
 * <p>The operation mix mirrors real usage:
 * <ul>
 *   <li>edge-drop split + move (the primary continuous-drag gesture),</li>
 *   <li>Shift-edge-drop split + clone (a fresh session is added via the host),</li>
 *   <li>cross-group tab-bar drops and same-group reorders,</li>
 *   <li>tab close and tab open,</li>
 *   <li>no-op content-center drops.</li>
 * </ul>
 * After every operation the tab inventory must match the expected count, every
 * tree group must still be rendered in the scene, no empty group may linger in
 * the tree, the rendered group set must exactly match the tree, and no
 * exception may have escaped on the JavaFX application thread.
 */
class TerminalSplitStressTest {

    private static volatile Throwable fxError;

    @BeforeAll
    static void startFx() {
        try {
            Platform.startup(() -> Thread.currentThread()
                .setUncaughtExceptionHandler((t, e) -> fxError = e));
        } catch (IllegalStateException ignored) {
        }
        Assumptions.assumeTrue(System.getenv("DISPLAY") != null, "No display available");
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get();
    }

    private static Tab tab(String title) {
        Tab t = new Tab(title);
        t.setContent(new Label(title));
        return t;
    }

    @Test
    void testContinuousDragMixNeverLosesTabsOrCrashes() throws Exception {
        for (long seed : new long[]{7, 42, 2024, 987654321L, 555}) {
            runSequence(seed, 5, 400);
        }
    }

    private void runSequence(long seed, int n, int operations) throws Exception {
        List<Tab> tabs = new ArrayList<>();
        for (int i = 0; i < n; i++) tabs.add(tab("t" + i));

        TerminalSplitArea area = new TerminalSplitArea();
        int[] count = {n};
        TerminalAreaHost host = new TerminalAreaHost() {
            @Override
            public ConnectionProfile getTabProfile(Tab tab) {
                return new ConnectionProfile("p", "h", 22, "u");
            }

            @Override
            public void openTab(ConnectionProfile profile) {
                // Mirrors MainController: a fresh session lands in the active group.
                Tab t = tab("clone-" + count[0]);
                count[0]++;
                area.addTab(t);
            }

            @Override
            public boolean isTerminalTab(Tab tab) {
                return true;
            }
        };
        area.setHost(host);
        onFx(() -> {
            tabs.forEach(area::addTab);
            return null;
        });
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(area, 720, 560));
            stage.show();
            return null;
        });
        pulse(area);

        Random rnd = new Random(seed);
        for (int op = 0; op < operations; op++) {
            onFx(() -> {
                List<TabGroupPane> groups = area.groupsForTest();
                List<Tab> all = area.getAllTabs();
                if (all.isEmpty()) {
                    area.addTab(tab("fresh"));
                    count[0]++;
                    return null;
                }
                int pick = rnd.nextInt(100);
                if (pick < 55) {
                    // Edge-drop split + move.
                    TabGroupPane target = groups.get(rnd.nextInt(groups.size()));
                    Orientation o = rnd.nextBoolean() ? Orientation.HORIZONTAL : Orientation.VERTICAL;
                    TabGroupPane newGroup = area.splitGroup(target, o, rnd.nextBoolean());
                    if (newGroup == null) return null;
                    Tab t = all.get(rnd.nextInt(all.size()));
                    TabGroupPane src = area.getGroupOfTab(t);
                    area.moveTab(t, newGroup, -1);
                    area.collapseIfEmpty(src);
                } else if (pick < 70) {
                    // Shift edge-drop: split + clone a fresh session via the host.
                    TabGroupPane target = groups.get(rnd.nextInt(groups.size()));
                    Orientation o = rnd.nextBoolean() ? Orientation.HORIZONTAL : Orientation.VERTICAL;
                    TabGroupPane newGroup = area.splitGroup(target, o, rnd.nextBoolean());
                    if (newGroup == null) return null;
                    // dropOnEdge's clone branch: activate(newGroup); host.openTab(profile);
                    area.activateForTest(newGroup);
                    host.openTab(new ConnectionProfile("p", "h", 22, "u"));
                } else if (pick < 85) {
                    // Cross-group or same-group tab-bar drop.
                    Tab t = all.get(rnd.nextInt(all.size()));
                    TabGroupPane target = groups.get(rnd.nextInt(groups.size()));
                    TabGroupPane src = area.getGroupOfTab(t);
                    if (src == target) {
                        area.moveTab(t, target, rnd.nextInt(Math.max(1, target.getTabPane().getTabs().size())));
                    } else {
                        area.moveTab(t, target, -1);
                        area.collapseIfEmpty(src);
                    }
                } else if (pick < 90) {
                    // Close a tab (the × button / Ctrl+W path).
                    Tab t = all.get(rnd.nextInt(all.size()));
                    area.removeTab(t);
                    count[0]--;
                } else if (pick < 95) {
                    // New SSH session to the active group.
                    area.addTab(tab("new-" + count[0]));
                    count[0]++;
                } else {
                    // No-op content-center drop.
                }
                return null;
            });
            pulse(area);

            int expected = count[0];
            int opIndex = op;
            String detail = onFx(() -> {
                StringBuilder sb = new StringBuilder("seed=").append(seed).append(" op=").append(opIndex)
                    .append(" expected=").append(expected)
                    .append(" groups=").append(area.groupsForTest().size());
                for (TabGroupPane g : area.groupsForTest()) {
                    sb.append(" [").append(g.getTabPane().getTabs().size()).append("]");
                }
                return sb.toString();
            });

            assertNull(fxError, "JavaFX thread exception: " + detail);

            List<TabGroupPane> treeGroups = onFx(area::groupsForTest);
            assertFalse(treeGroups.isEmpty(), "tree collapsed entirely: " + detail);

            int total = onFx(area::getTabCount);
            assertEquals(expected, total, "tab inventory mismatch: " + detail);

            for (TabGroupPane g : treeGroups) {
                int size = onFx(() -> g.getTabPane().getTabs().size());
                assertTrue(size >= 1, "empty group leaked into tree: " + detail);
                Boolean rendered = onFx(() -> g.getTabPane().getScene() != null);
                assertTrue(rendered, "tree group detached from scene: " + detail);
            }

            // The rendered scene must contain exactly the tree's groups.
            int renderedCount = onFx(() -> renderedGroups(area).size());
            assertEquals(treeGroups.size(), renderedCount, "scene/tree group mismatch: " + detail);
        }

        // Tear down cleanly on the FX thread; never call Platform.exit() here.
        onFx(() -> {
            area.clearAllTabs();
            return null;
        });
        pulse(area);
    }

    /** Flush queued runLater collapses, then force a full layout pass. */
    private static void pulse(TerminalSplitArea area) throws Exception {
        onFx(() -> {
            area.applyCss();
            area.layout();
            return null;
        });
    }

    /** All {@link TabGroupPane}s currently rendered in the scene graph under {@code area}. */
    private static List<TabGroupPane> renderedGroups(Node start) {
        List<TabGroupPane> out = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            if (node instanceof TabGroupPane g) {
                out.add(g);
                continue;
            }
            if (!(node instanceof Parent p) || !seen.add(node)) continue;
            if (node instanceof SplitPane sp && sp.getSkin() != null
                && sp.getSkin().getNode() instanceof Parent skin) {
                for (Node c : skin.getChildrenUnmodifiable()) stack.push(c);
            }
            for (Node c : p.getChildrenUnmodifiable()) stack.push(c);
        }
        return out;
    }
}
