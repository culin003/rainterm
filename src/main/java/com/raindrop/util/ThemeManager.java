package com.raindrop.util;

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
