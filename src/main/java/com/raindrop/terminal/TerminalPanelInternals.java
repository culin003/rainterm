package com.raindrop.terminal;

import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.ui.TerminalPanel;
import javafx.animation.Timeline;

import java.lang.reflect.Field;

/**
 * The single place where Raindrop reaches into {@link TerminalPanel}'s private
 * state. Everything here depends on jeditermfx implementation details rather
 * than its public API, so a library upgrade breaks these and nothing else —
 * keeping them in one file makes that blast radius auditable.
 *
 * <p>Every method is best-effort: a missing or renamed field degrades behavior
 * (documented per method) instead of failing the operation. Field names are
 * verified against jeditermfx 1.1.x.
 */
final class TerminalPanelInternals {

    private static final String FIELD_STYLE_STATE = "myStyleState";
    private static final String FIELD_REPAINT_TIMELINE = "myRepaintTimeLine";

    private TerminalPanelInternals() {}

    /**
     * Replace the default {@link TextStyle} on the panel's StyleState.
     *
     * <p>The StyleState on the TerminalTextBuffer snapshots the settings provider's
     * default style at construction time and exposes no public setter. Without this,
     * a live theme switch leaves cells that never had explicit SGR colors (the SSH
     * banner / MOTD) painted with the previous theme's background.
     *
     * <p>Degrades to: banner cells keep the old background until they are rewritten.
     */
    static void setDefaultStyle(TerminalPanel panel, TextStyle style) {
        if (panel == null || style == null) return;
        try {
            Object styleState = readField(panel, FIELD_STYLE_STATE);
            if (styleState == null) return;
            styleState.getClass()
                .getMethod("setDefaultStyle", TextStyle.class)
                .invoke(styleState, style);
        } catch (ReflectiveOperationException ignored) {
            // Next paint still resolves the new window background via
            // getWindowBackground(); only banner cells retain the old one.
        }
    }

    /**
     * Stop the panel's internal repaint timer before it becomes unreachable.
     *
     * <p>jeditermfx's WeakRedrawTimer holds the TerminalPanel through a WeakReference.
     * Once that reference clears, its {@code handle()} casts the ActionEvent source to
     * Timeline, but JavaFX sometimes delivers a KeyFrame — a ClassCastException on a
     * thread we don't control. Stopping the timer first avoids the race.
     *
     * <p>Degrades to: the CCE can still fire after the panel is collected.
     */
    static void stopRepaintTimer(TerminalPanel panel) {
        if (panel == null) return;
        try {
            if (readField(panel, FIELD_REPAINT_TIMELINE) instanceof Timeline timeline) {
                timeline.stop();
            }
        } catch (ReflectiveOperationException ignored) {
            // Timer keeps running until GC; see method contract.
        }
    }

    private static Object readField(TerminalPanel panel, String name) throws ReflectiveOperationException {
        Field field = TerminalPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }
}
