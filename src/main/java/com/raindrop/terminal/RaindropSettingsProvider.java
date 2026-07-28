package com.raindrop.terminal;

import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import javafx.scene.text.Font;

/**
 * Raindrop's minimal customization on top of JediTermFX's DefaultSettingsProvider.
 * Reads foreground/background from {@link TerminalTheme} and font size from ConfigManager.
 *
 * <p>Fields are volatile — a live theme switch calls {@link #setTheme(TerminalTheme)} from
 * the JavaFX Application Thread while the emulator's paint thread may be resolving
 * default background/foreground.
 */
public class RaindropSettingsProvider extends DefaultSettingsProvider {
    private volatile TerminalTheme theme;
    private volatile double fontSize;

    public RaindropSettingsProvider(TerminalTheme theme, double fontSize) {
        this.theme = theme;
        this.fontSize = fontSize;
    }

    public void setTheme(TerminalTheme theme) {
        this.theme = theme;
    }

    public void setFontSize(double fontSize) {
        this.fontSize = fontSize;
    }

    public TerminalTheme getTheme() {
        return theme;
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return hexToColor(theme.getBackground());
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return hexToColor(theme.getForeground());
    }

    /**
     * jeditermfx's default {@code getDefaultStyle()} returns
     * {@code new TextStyle(BLACK, WHITE)} — a hard-coded white background baked into
     * the StyleState. Characters whose SGR state never explicitly sets fg/bg (e.g. the
     * SSH banner / MOTD before the shell prompt writes its own colors) are painted
     * with that white background on top of our theme-colored canvas, producing a
     * white strip in the top-left on dark themes.
     *
     * <p>We swap in a style backed by the current theme so the untouched cells match
     * the terminal window background instead of pure white. We can't return a style
     * with {@code null} fg/bg: {@link com.techsenger.jeditermfx.core.model.StyleState#getDefaultForeground()}
     * calls {@code Objects.requireNonNull} on it and the cursor draw path (INVERSE
     * style) NPEs.
     */
    @Override
    public TextStyle getDefaultStyle() {
        return new TextStyle(hexToColor(theme.getForeground()), hexToColor(theme.getBackground()));
    }

    @Override
    public Font getTerminalFont() {
        return Font.font(com.raindrop.util.FontManager.monoCjkFamily(), fontSize);
    }

    @Override
    public float getTerminalFontSize() {
        return (float) fontSize;
    }

    private static TerminalColor hexToColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        return new TerminalColor(r, g, b);
    }
}
