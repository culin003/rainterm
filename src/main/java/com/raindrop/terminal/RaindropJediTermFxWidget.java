package com.raindrop.terminal;

import com.techsenger.jeditermfx.core.TerminalStarter;
import com.techsenger.jeditermfx.core.model.StyleState;
import com.techsenger.jeditermfx.core.model.TerminalTextBuffer;
import com.techsenger.jeditermfx.core.util.Platform;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.TerminalPanel;
import com.techsenger.jeditermfx.ui.settings.SettingsProvider;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * JediTermFX widget wired to {@link RaindropTerminalPanel} so the context-menu
 * popup is cached across right-clicks. Also exposes a hook to prewarm the popup
 * peer once the widget is attached to a Scene.
 */
public class RaindropJediTermFxWidget extends JediTermFxWidget {

    public RaindropJediTermFxWidget(int cols, int rows, SettingsProvider settings) {
        super(cols, rows, settings);
        installWindowsControlKeyWorkaround();
    }

    /**
     * On Windows, JavaFX reports an empty {@code text} on the KEY_PRESSED event
     * for {@code Ctrl+letter} combinations, so JediTermFX's {@code processTerminalKeyPressed}
     * never sees the ISO control character and the key is silently dropped —
     * {@code Ctrl+C} cannot interrupt the remote shell (see techsenger/jeditermfx#19,
     * fixed upstream only for {@code Ctrl+C}). A Ctrl+letter KEY_TYPED is also skipped
     * by JediTermFX because it explicitly ignores ISO control characters there.
     *
     * <p>This filter runs <em>after</em> TerminalPanel's own KEY_PRESSED filter (it is
     * registered later on the same Canvas), so keys already consumed by JediTermFX
     * (Copy {@code Ctrl+Shift+C}, Paste {@code Ctrl+Shift+V}, Clear Buffer {@code Ctrl+L}…)
     * never reach it. Only the unhandled Ctrl+letter presses are re-mapped to their
     * ASCII control byte, matching Linux/macOS behavior.
     */
    private void installWindowsControlKeyWorkaround() {
        if (!Platform.isWindows()) return;
        Canvas canvas = getTerminalPanel().getCanvas();
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, this::handleWindowsControlKey);
    }

    private void handleWindowsControlKey(KeyEvent e) {
        if (e.isConsumed() || !e.isControlDown() || e.isAltDown() || e.isMetaDown()) return;
        byte ctrl = ctrlCodeOf(e.getCode());
        if (ctrl < 0) return;
        TerminalStarter starter = getTerminalStarter();
        if (starter == null) return;
        starter.sendBytes(new byte[]{ctrl}, true);
        e.consume();
    }

    /**
     * Map a {@code Ctrl+key} to its ASCII control byte, or -1 when the key carries
     * no control meaning. Shift does not change the control byte ({@code Ctrl+Shift+U}
     * is still SYN/U); keys bound to menu shortcuts such as Copy/Paste/Clear Buffer
     * never reach this filter because the panel's own handler consumes them first.
     */
    static byte ctrlCodeOf(KeyCode code) {
        if (code.isLetterKey()) {
            return (byte) (code.getCode() - KeyCode.A.getCode() + 1);
        }
        return switch (code) {
            case SPACE -> 0;
            case OPEN_BRACKET -> 27;
            case BACK_SLASH -> 28;
            case CLOSE_BRACKET -> 29;
            case CIRCUMFLEX -> 30;
            case UNDERSCORE -> 31;
            default -> -1;
        };
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
