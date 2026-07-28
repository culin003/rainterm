package com.raindrop.terminal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terminal color themes.
 */
public class TerminalTheme {
    private final String name;
    private final String background;
    private final String foreground;
    private final Map<Integer, String> ansiColors;

    private TerminalTheme(String name, String background, String foreground, Map<Integer, String> ansiColors) {
        this.name = name;
        this.background = background;
        this.foreground = foreground;
        this.ansiColors = ansiColors;
    }

    public String getName() { return name; }
    public String getBackground() { return background; }
    public String getForeground() { return foreground; }
    public Map<Integer, String> getAnsiColors() { return ansiColors; }

    /**
     * Get CSS style string for terminal.
     */
    public String getCssStyle() {
        return String.format(
            "-fx-control-inner-background: %s; -fx-text-fill: %s; -fx-font-family: '%s', 'Monospace'; -fx-font-size: 14px;",
            background, foreground, com.raindrop.util.FontManager.monoCjkFamily()
        );
    }

    // Preset themes
    public static final TerminalTheme DARK = new TerminalTheme(
        "Dark", "#1e1e1e", "#cccccc", createDarkColors()
    );

    public static final TerminalTheme LIGHT = new TerminalTheme(
        "Light", "#ffffff", "#000000", createLightColors()
    );

    public static final TerminalTheme GREEN_ON_BLACK = new TerminalTheme(
        "Green on Black", "#000000", "#00ff00", createGreenOnBlackColors()
    );

    public static final TerminalTheme SOLARIZED_DARK = new TerminalTheme(
        "Solarized Dark", "#002b36", "#839496", createSolarizedDarkColors()
    );

    private static Map<Integer, String> createDarkColors() {
        Map<Integer, String> colors = new ConcurrentHashMap<>();
        colors.put(0, "#000000");   // Black
        colors.put(1, "#cc0000");   // Red
        colors.put(2, "#00cc00");   // Green
        colors.put(3, "#cccc00");   // Yellow
        colors.put(4, "#0000cc");   // Blue
        colors.put(5, "#cc00cc");   // Magenta
        colors.put(6, "#00cccc");   // Cyan
        colors.put(7, "#cccccc");   // White
        return colors;
    }

    private static Map<Integer, String> createLightColors() {
        Map<Integer, String> colors = new ConcurrentHashMap<>();
        colors.put(0, "#000000");
        colors.put(1, "#cc0000");
        colors.put(2, "#009900");
        colors.put(3, "#999900");
        colors.put(4, "#0000cc");
        colors.put(5, "#cc00cc");
        colors.put(6, "#009999");
        colors.put(7, "#999999");
        return colors;
    }

    private static Map<Integer, String> createGreenOnBlackColors() {
        Map<Integer, String> colors = new ConcurrentHashMap<>();
        colors.put(0, "#000000");
        colors.put(1, "#ff0000");
        colors.put(2, "#00ff00");
        colors.put(3, "#ffff00");
        colors.put(4, "#0000ff");
        colors.put(5, "#ff00ff");
        colors.put(6, "#00ffff");
        colors.put(7, "#00ff00");
        return colors;
    }

    private static Map<Integer, String> createSolarizedDarkColors() {
        Map<Integer, String> colors = new ConcurrentHashMap<>();
        colors.put(0, "#073642");
        colors.put(1, "#dc322f");
        colors.put(2, "#859900");
        colors.put(3, "#b58900");
        colors.put(4, "#268bd2");
        colors.put(5, "#d33682");
        colors.put(6, "#2aa198");
        colors.put(7, "#eee8d5");
        return colors;
    }

    /**
     * Get all available themes.
     */
    public static TerminalTheme[] getAllThemes() {
        return new TerminalTheme[]{ DARK, LIGHT, GREEN_ON_BLACK, SOLARIZED_DARK };
    }

    @Override
    public String toString() {
        return name;
    }
}
