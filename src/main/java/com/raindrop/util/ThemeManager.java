package com.raindrop.util;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Single source of truth for looking up and applying the active CSS theme to
 * any JavaFX {@link Scene}, {@link DialogPane}, or {@link Alert}.
 *
 * <p>Popup Stages and Alerts are created with fresh Scenes that do not inherit
 * the primary Scene's stylesheets. Every popup site must funnel through this
 * class so all windows look consistent with the current terminal theme.
 */
public final class ThemeManager {
    public static final int MIN_UI_FONT_SIZE = 9;
    public static final int MAX_UI_FONT_SIZE = 24;
    public static final int DEFAULT_UI_FONT_SIZE = 13;

    private static final String BASE_STYLE_KEY = "raindrop.baseStyle";

    private ThemeManager() {}

    /** Look up the CSS resource URL for the current terminal theme, or fall back to dark. */
    public static String currentStylesheetUrl() {
        String themeKey = ConfigManager.getInstance().get(ConfigManager.KEY_TERMINAL_THEME, "dark");
        return stylesheetUrlFor(themeKey);
    }

    public static String stylesheetUrlFor(String themeKey) {
        String css = switch (themeKey == null ? "dark" : themeKey.toLowerCase()) {
            case "light" -> "/css/light.css";
            case "solarized-dark" -> "/css/solarized-dark.css";
            case "green-on-black" -> "/css/green-on-black.css";
            default -> "/css/dark.css";
        };
        var url = ThemeManager.class.getResource(css);
        return url == null ? null : url.toExternalForm();
    }

    /** Apply the current theme to a Scene, replacing any previous stylesheets. */
    public static void apply(Scene scene) {
        if (scene == null) return;
        String url = currentStylesheetUrl();
        if (url == null) return;
        scene.getStylesheets().clear();
        scene.getStylesheets().add(url);
        applyUiFont(scene);
    }

    /**
     * Push the configured UI font family/size onto a Scene's root as an inline style.
     * Inline style on the root beats stylesheet rules for the inherited {@code -fx-font-*}
     * properties, so controls pick it up without every theme CSS having to declare it.
     *
     * <p>Some FXML roots ship their own inline style (background colors), so the node's
     * original style is stashed on first call and always re-prepended — otherwise a
     * second call would drop it or stack duplicate font declarations.
     */
    public static void applyUiFont(Scene scene) {
        if (scene == null || scene.getRoot() == null) return;
        Node root = scene.getRoot();
        String baseStyle = (String) root.getProperties()
            .computeIfAbsent(BASE_STYLE_KEY, k -> root.getStyle() == null ? "" : root.getStyle());

        ConfigManager cfg = ConfigManager.getInstance();
        String family = cfg.get(ConfigManager.KEY_UI_FONT_FAMILY, "");
        String size = cfg.get(ConfigManager.KEY_UI_FONT_SIZE, "");
        if (family.isBlank() && size.isBlank()) {
            root.setStyle(baseStyle);
            return;
        }
        StringBuilder style = new StringBuilder(baseStyle);
        if (!size.isBlank()) {
            style.append(" -fx-font-size: ").append(size).append("px;");
        }
        if (!family.isBlank()) {
            style.append(" -fx-font-family: \"").append(family).append("\";");
        }
        root.setStyle(style.toString());
    }

    /** Apply the current theme to a DialogPane (used by JavaFX {@link Alert}s). */
    public static void apply(DialogPane pane) {
        if (pane == null) return;
        String url = currentStylesheetUrl();
        if (url == null) return;
        pane.getStylesheets().clear();
        pane.getStylesheets().add(url);
    }

    /** Apply the current theme to a JavaFX {@link Alert} — themes both the Alert's
     *  DialogPane and the underlying Stage's Scene. */
    public static void apply(Alert alert) {
        if (alert == null) return;
        apply(alert.getDialogPane());
        if (alert.getDialogPane().getScene() != null) {
            apply(alert.getDialogPane().getScene());
        }
    }

    /**
     * Re-apply the current theme to every currently-open JavaFX window so that a
     * live theme switch propagates to child popup Stages, not just the primary one.
     */
    public static void applyToAllWindows() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.getScene() != null) {
                apply(s.getScene());
            }
        }
    }
}
