package com.raindrop.terminal;

import com.techsenger.jeditermfx.core.model.StyleState;
import com.techsenger.jeditermfx.core.model.TerminalTextBuffer;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.TerminalPanel;
import com.techsenger.jeditermfx.ui.settings.SettingsProvider;

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

    /** Re-measure the character grid after the settings provider's font changed. */
    public void refreshFont() {
        TerminalPanel p = getTerminalPanel();
        if (p instanceof RaindropTerminalPanel rp) {
            rp.refreshFont();
        }
    }

    /**
     * Refresh the buffer's default text style so a live theme switch also recolors
     * cells that were painted without explicit SGR colors (SSH banner / MOTD).
     */
    public void refreshDefaultStyle(RaindropSettingsProvider settings) {
        TerminalPanelInternals.setDefaultStyle(getTerminalPanel(), settings.getDefaultStyle());
    }

    @Override
    public void close() {
        TerminalPanelInternals.stopRepaintTimer(getTerminalPanel());
        super.close();
    }
}
