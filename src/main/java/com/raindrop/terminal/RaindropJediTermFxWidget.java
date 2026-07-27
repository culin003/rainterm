package com.raindrop.terminal;

import com.techsenger.jeditermfx.core.model.StyleState;
import com.techsenger.jeditermfx.core.model.TerminalTextBuffer;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.TerminalPanel;
import com.techsenger.jeditermfx.ui.settings.SettingsProvider;
import javafx.animation.Timeline;

/**
 * JediTermFX widget wired to {@link RaindropTerminalPanel} so the context-menu
 * popup is cached across right-clicks. Also exposes a hook to prewarm the popup
 * peer once the widget is attached to a Scene.
 */
public class RaindropJediTermFxWidget extends JediTermFxWidget {

    public RaindropJediTermFxWidget(int cols, int rows, SettingsProvider settings) {
        super(cols, rows, settings);
    }

    @Override
    protected TerminalPanel createTerminalPanel(SettingsProvider settings, StyleState style, TerminalTextBuffer buffer) {
        return new RaindropTerminalPanel(settings, buffer, style);
    }

    public void prewarmContextMenu() {
        TerminalPanel p = getTerminalPanel();
        if (p instanceof RaindropTerminalPanel rp) {
            rp.prewarmPopup(this);
        }
    }

    @Override
    public void close() {
        // Stop the internal repaint timer before the TerminalPanel is garbage
        // collected. JediTermFX's WeakRedrawTimer uses a WeakReference to the
        // TerminalPanel; when the ref is cleared, handle() tries to cast the
        // ActionEvent source to Timeline, but JavaFX sometimes delivers a
        // KeyFrame instead, causing a ClassCastException. Stopping the timer
        // preemptively avoids this.
        stopRepaintTimer();
        super.close();
    }

    private void stopRepaintTimer() {
        TerminalPanel panel = getTerminalPanel();
        if (panel == null) return;
        try {
            var field = TerminalPanel.class.getDeclaredField("myRepaintTimeLine");
            field.setAccessible(true);
            Timeline timeline = (Timeline) field.get(panel);
            if (timeline != null) {
                timeline.stop();
            }
        } catch (Exception ignored) {
            // Best-effort: the field name is stable across jeditermfx 1.1.x.
        }
    }
}
