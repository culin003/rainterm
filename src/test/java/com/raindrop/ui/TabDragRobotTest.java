package com.raindrop.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.AWTException;
import java.awt.event.InputEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the real drag gesture: it exercises {@code
 * MouseEvent.DRAG_DETECTED} (the system fires it, then our filter must call
 * {@code startDragAndDrop}), plus the ongoing drag-over/drop events. This is
 * the scenario that used to throw {@code IllegalStateException: Cannot start
 * drag and drop outside of DRAG_DETECTED event handler}.
 *
 * <p>Input is injected with {@link java.awt.Robot} (raw X events) rather than
 * the JavaFX {@link javafx.scene.robot.Robot}: once the native drag loop
 * starts it blocks the FX thread, so any further FX-thread {@code Robot} call
 * would deadlock.
 *
 * <p>Requires a real X11 display, so it is skipped when none is available.
 */
class TabDragRobotTest {

    private static java.awt.Robot robot;
    private static final AtomicReference<Throwable> fxError = new AtomicReference<>();
    private static Thread.UncaughtExceptionHandler prevHandler;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(System.getenv("DISPLAY") != null, "No display available");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
        }
        prevHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (t.getName().contains("JavaFX")) fxError.compareAndSet(null, e);
        });
        try {
            robot = new java.awt.Robot();
        } catch (AWTException e) {
            Assumptions.assumeTrue(false, "AWT Robot unavailable: " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        if (prevHandler != null) Thread.setDefaultUncaughtExceptionHandler(prevHandler);
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

    private static TerminalAreaHost fakeHost() {
        return new TerminalAreaHost() {
            @Override
            public com.raindrop.storage.ConnectionProfile getTabProfile(Tab tab) {
                return null;
            }

            @Override
            public void openTab(com.raindrop.storage.ConnectionProfile profile) {
            }

            @Override
            public boolean isTerminalTab(Tab tab) {
                return true;
            }
        };
    }

    private static void show(TerminalSplitArea area) throws Exception {
        onFx(() -> {
            Stage stage = new Stage();
            Scene scene = new Scene(area, 800, 600);
            stage.setScene(scene);
            stage.show();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(300); // let the first layout pass complete
    }

    /** Screen coordinates of the given node's center. */
    private static Point2D screenCenter(Node node) throws Exception {
        return onFx(() -> {
            Bounds b = node.localToScreen(node.getBoundsInLocal());
            return new Point2D(b.getMinX() + b.getWidth() / 2, b.getMinY() + b.getHeight() / 2);
        });
    }

    private static Point2D contentRightScreen(TerminalSplitArea area) throws Exception {
        return onFx(() -> {
            Node content = area.getActiveTabPane().lookup(".tab-content-area");
            Bounds cb = content.localToScreen(content.getBoundsInLocal());
            return new Point2D(cb.getMaxX() - 10, cb.getMinY() + cb.getHeight() / 2);
        });
    }

    @Test
    void testDragTabHeaderToRightEdgeSplitsGroup() throws Exception {
        fxError.set(null);
        TerminalSplitArea area = new TerminalSplitArea();
        Tab a = tab("Alpha");
        Tab b = tab("Beta");
        area.setHost(fakeHost());
        onFx(() -> {
            area.addTab(a);
            area.addTab(b);
            return null;
        });
        show(area);

        Point2D header = screenCenter(area.getActiveTabPane().lookup(".tab"));
        Point2D right = contentRightScreen(area);

        robot.mouseMove((int) header.getX(), (int) header.getY());
        Thread.sleep(100);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseMove((int) header.getX() + 40, (int) header.getY());
        Thread.sleep(150);
        robot.mouseMove((int) right.getX(), (int) right.getY());
        Thread.sleep(300);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(300);
        onFx(() -> null);

        assertNull(fxError.get(), () -> "FX thread exception during drag: " + fxError.get());
        Integer groups = onFx(area::groupCountForTest);
        assertEquals(2, groups, "dropping onto the right edge should create a split");
    }

    @Test
    void testDragFromTerminalContentDoesNotStartTabDrag() throws Exception {
        fxError.set(null);
        TerminalSplitArea area = new TerminalSplitArea();
        Tab a = tab("Alpha");
        area.setHost(fakeHost());
        onFx(() -> {
            area.addTab(a);
            return null;
        });
        show(area);

        Point2D center = onFx(() -> {
            Node content = area.getActiveTabPane().lookup(".tab-content-area");
            Bounds cb = content.localToScreen(content.getBoundsInLocal());
            return new Point2D(cb.getMinX() + cb.getWidth() / 2, cb.getMinY() + cb.getHeight() / 2);
        });

        robot.mouseMove((int) center.getX(), (int) center.getY());
        Thread.sleep(100);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseMove((int) center.getX() + 40, (int) center.getY());
        Thread.sleep(150);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(300);
        onFx(() -> null);

        assertNull(fxError.get(), () -> "FX thread exception during drag: " + fxError.get());
        Integer groups = onFx(area::groupCountForTest);
        assertEquals(1, groups, "dragging the terminal content must not start a tab drag");
    }
}
