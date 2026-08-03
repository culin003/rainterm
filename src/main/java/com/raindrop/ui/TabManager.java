package com.raindrop.ui;

import com.raindrop.core.ConnectionManager;
import com.raindrop.core.SshSession;
import com.raindrop.core.TaskExecutor;
import com.raindrop.storage.ConnectionProfile;
import com.raindrop.terminal.RaindropJediTermFxWidget;
import com.raindrop.terminal.RaindropSettingsProvider;
import com.raindrop.terminal.SshTtyConnector;
import com.raindrop.terminal.TerminalTheme;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.I18nManager;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages SSH terminal tabs backed exclusively by JediTermFX.
 *
 * <p>{@link ConnectionManager} is the single source of truth for the tab→session
 * mapping (keyed by Tab identity). Widgets and per-tab profile stay local to
 * TabManager because they are UI concerns.
 *
 * <p>On disconnect the tab is replaced with a "Connection lost — Reconnect" panel;
 * clicking Reconnect rebuilds the session and re-attaches the widget.
 */
public class TabManager {
    public static final int MIN_FONT_SIZE = 8;
    public static final int MAX_FONT_SIZE = 32;
    public static final int DEFAULT_FONT_SIZE = 14;

    private final TabPane tabPane;
    private final ConnectionManager connectionManager;
    private final MainController mainController;
    private final Map<Tab, JediTermFxWidget> widgets = new ConcurrentHashMap<>();
    private final Map<Tab, ConnectionProfile> tabProfile = new ConcurrentHashMap<>();
    private final Map<Tab, RaindropSettingsProvider> tabSettings = new ConcurrentHashMap<>();
    private final Map<Tab, SshSession> connectingSessions = new ConcurrentHashMap<>();

    public TabManager(TabPane tabPane, ConnectionManager connectionManager, MainController mainController) {
        this.tabPane = tabPane;
        this.connectionManager = connectionManager;
        this.mainController = mainController;
    }

    public void openTab(ConnectionProfile profile) {
        openTabJediTermFx(profile, ConfigManager.getInstance(), null);
    }

    /**
     * @param existingTab when non-null this is a reconnect: reuse the tab instead of appending a new one.
     */
    private void openTabJediTermFx(ConnectionProfile profile, ConfigManager cfg, Tab existingTab) {
        TerminalTheme theme = resolveTheme(cfg.get(ConfigManager.KEY_TERMINAL_THEME, "dark"));
        double fontSize = cfg.getInt(ConfigManager.KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        String fontFamily = cfg.get(ConfigManager.KEY_TERMINAL_FONT_FAMILY, "");
        final Charset effectiveCharset = resolveCharset(profile, cfg);

        RaindropSettingsProvider settings = new RaindropSettingsProvider(theme, fontSize, fontFamily);
        JediTermFxWidget widget = new RaindropJediTermFxWidget(120, 40, settings);
        // Paint the widget's outer StackPane with the theme background so the
        // brief moment before the Canvas has finished its first repaint doesn't
        // flash white on a dark theme. Also covers any padding around the canvas.
        applyBackgroundStyle(widget.getPane(), theme.getBackground());
        installZoomHandlers(widget.getPane());

        Label placeholder = new Label("Connecting to " + profile.getHost() + "...");
        placeholder.setMaxWidth(Double.MAX_VALUE);
        placeholder.setMaxHeight(Double.MAX_VALUE);
        placeholder.setStyle(
            "-fx-background-color: " + theme.getBackground() + ";"
                + " -fx-text-fill: " + theme.getForeground() + ";"
                + " -fx-padding: 12;"
        );
        final Tab tab;
        if (existingTab != null) {
            tab = existingTab;
            tab.setContent(placeholder);
        } else {
            tab = new Tab(profile.getName() + " (" + profile.getHost() + ")");
            tab.setContent(placeholder);
            tab.setClosable(true);
            tab.setOnClosed(e -> closeTabResources(tab));
            setupTabContextMenu(tab);
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
        }
        tabProfile.put(tab, profile);
        tabSettings.put(tab, settings);
        widgets.put(tab, widget);

        TaskExecutor.submit(() -> {
            try {
                SshSession session = new SshSession(profile);
                connectingSessions.put(tab, session);
                session.connect();
                connectingSessions.remove(tab);
                connectionManager.register(tab, session);

                SshTtyConnector connector = new SshTtyConnector(
                    session.getShell(), effectiveCharset,
                    profile.getUsername() + "@" + profile.getHost());
                connector.setOnDisconnect(() -> handleDisconnect(tab, session));

                Platform.runLater(() -> {
                    widget.setTtyConnector(connector);
                    widget.start();
                    tab.setContent(widget.getPane());
                    tab.setText(profile.getName() + " (" + profile.getHost() + ") ✓");
                    mainController.updateStatus("Connected to " + profile.getHost());
                    // Prewarm the context-menu popup peer on the next FX pulse so the
                    // first user right-click doesn't pay the ~300ms cold-start cost.
                    Platform.runLater(() -> {
                        if (widget instanceof RaindropJediTermFxWidget rw) rw.prewarmContextMenu();
                    });
                });
            } catch (IOException e) {
                SshSession failed = connectingSessions.remove(tab);
                if (failed != null) {
                    try { failed.disconnect(); } catch (Exception ignored) {}
                }
                Platform.runLater(() -> {
                    tab.setContent(disconnectedPanel(profile, "Connection failed: " + e.getMessage(), tab));
                    tab.setText(profile.getName() + " (" + profile.getHost() + ") ✗");
                    mainController.showError("Failed to connect: " + e.getMessage());
                });
            }
        });
    }

    private void handleDisconnect(Tab tab, SshSession session) {
        ConnectionProfile profile = tabProfile.getOrDefault(tab, session.getProfile());
        Platform.runLater(() -> {
            tab.setText(profile.getName() + " (" + profile.getHost() + ") ✗");
            tab.setContent(disconnectedPanel(profile, "Connection lost.", tab));
        });
        JediTermFxWidget w = widgets.remove(tab);
        if (w != null) try { w.close(); } catch (Exception ignored) {}
        try { session.disconnect(); } catch (Exception ignored) {}
        connectionManager.unregister(tab);
    }

    /**
     * Reconnect an existing tab: reuse the same Tab node but rebuild session + widget in place.
     * Called from the "Reconnect" button on the disconnected panel.
     */
    public void reconnectTab(Tab tab) {
        ConnectionProfile profile = tabProfile.get(tab);
        if (profile == null) return;
        JediTermFxWidget w = widgets.remove(tab);
        if (w != null) try { w.close(); } catch (Exception ignored) {}
        SshSession old = connectionManager.unregister(tab);
        if (old != null) try { old.disconnect(); } catch (Exception ignored) {}
        openTabJediTermFx(profile, ConfigManager.getInstance(), tab);
    }

    private Parent disconnectedPanel(ConnectionProfile profile, String message, Tab tab) {
        Label title = new Label("Disconnected: " + profile.getName() + " (" + profile.getHost() + ")");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label detail = new Label(message);
        Button reconnect = new Button("Reconnect");
        reconnect.setOnAction(e -> reconnectTab(tab));
        Button close = new Button("Close tab");
        close.setOnAction(e -> closeTab(tab));

        VBox box = new VBox(12, title, detail, reconnect, close);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        return box;
    }

    /** Per-profile encoding wins over the global default; blank means "not set". */
    private static Charset resolveCharset(ConnectionProfile profile, ConfigManager cfg) {
        String profileEncoding = profile.getEncoding();
        String name = (profileEncoding != null && !profileEncoding.isBlank())
            ? profileEncoding
            : cfg.get(ConfigManager.KEY_DEFAULT_ENCODING, "UTF-8");
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return Charset.forName("UTF-8");
        }
    }

    private TerminalTheme resolveTheme(String name) {
        return switch (name == null ? "dark" : name.toLowerCase()) {
            case "light" -> TerminalTheme.LIGHT;
            case "green-on-black" -> TerminalTheme.GREEN_ON_BLACK;
            case "solarized-dark" -> TerminalTheme.SOLARIZED_DARK;
            default -> TerminalTheme.DARK;
        };
    }

    private void closeTabResources(Tab tab) {
        JediTermFxWidget widget = widgets.remove(tab);
        if (widget != null) {
            try { widget.close(); } catch (Exception ignored) {}
        }
        // Disconnect session if already registered
        SshSession session = connectionManager.unregister(tab);
        if (session != null) {
            try { session.disconnect(); } catch (Exception ignored) {}
        }
        // Also disconnect if still connecting
        SshSession connecting = connectingSessions.remove(tab);
        if (connecting != null) {
            try { connecting.disconnect(); } catch (Exception ignored) {}
        }
        tabProfile.remove(tab);
        tabSettings.remove(tab);
    }

    public void closeTab(Tab tab) {
        closeTabResources(tab);
        tabPane.getTabs().remove(tab);
    }

    public void closeAllTabs() {
        for (Tab tab : new ArrayList<>(tabPane.getTabs())) {
            closeTabResources(tab);
        }
        widgets.clear();
        tabProfile.clear();
        tabSettings.clear();
        tabPane.getTabs().clear();
    }

    public SshSession getSession(Tab tab) {
        return connectionManager.getSession(tab);
    }

    public Tab getCurrentTab() {
        return tabPane.getSelectionModel().getSelectedItem();
    }

    public int getActiveTabCount() {
        return connectionManager.getActiveCount();
    }

    public com.raindrop.storage.ConnectionProfile getTabProfile(Tab tab) {
        return tabProfile.get(tab);
    }

    /**
     * Live-swap the terminal theme on every open jeditermfx tab. Updates the
     * per-tab {@link RaindropSettingsProvider} (so the emulator resolves new
     * default fg/bg on next paint), refreshes the buffer's StyleState default
     * style (so cells drawn before the switch pick up the new theme too),
     * repaints the canvas, and re-styles the widget's outer pane so no white
     * gutter shows through.
     */
    public void applyTerminalTheme(String themeKey) {
        TerminalTheme theme = resolveTheme(themeKey);
        Platform.runLater(() -> {
            for (Map.Entry<Tab, RaindropSettingsProvider> entry : tabSettings.entrySet()) {
                Tab tab = entry.getKey();
                RaindropSettingsProvider settings = entry.getValue();
                settings.setTheme(theme);
                JediTermFxWidget widget = widgets.get(tab);
                if (widget != null) {
                    applyBackgroundStyle(widget.getPane(), theme.getBackground());
                    if (widget instanceof RaindropJediTermFxWidget rw) {
                        rw.refreshDefaultStyle(settings);
                        if (rw.getTerminalPanel() != null) {
                            rw.getTerminalPanel().repaint();
                        }
                    }
                }
            }
        });
    }

    /**
     * Live-swap the terminal font family + size on every open tab, so a settings
     * save doesn't require reopening tabs. The cell grid must be re-measured after
     * the font changes, otherwise the canvas keeps the old character advance.
     */
    public void applyTerminalFont(String family, double size) {
        Platform.runLater(() -> {
            for (Map.Entry<Tab, RaindropSettingsProvider> entry : tabSettings.entrySet()) {
                RaindropSettingsProvider settings = entry.getValue();
                settings.setFontFamily(family);
                settings.setFontSize(size);
                JediTermFxWidget widget = widgets.get(entry.getKey());
                if (widget instanceof RaindropJediTermFxWidget rw) {
                    rw.refreshFont();
                }
            }
        });
    }

    /**
     * Nudge the terminal font size by {@code delta} steps, clamped to the same
     * range the settings dialog allows. Persists the result so the zoom survives
     * a restart and stays in sync with the settings dialog.
     */
    public void adjustFontSize(int delta) {
        ConfigManager cfg = ConfigManager.getInstance();
        int current = cfg.getInt(ConfigManager.KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        int next = Math.clamp(current + delta, MIN_FONT_SIZE, MAX_FONT_SIZE);
        if (next == current) return;
        cfg.set(ConfigManager.KEY_FONT_SIZE, String.valueOf(next));
        applyTerminalFont(cfg.get(ConfigManager.KEY_TERMINAL_FONT_FAMILY, ""), next);
        mainController.updateStatus("Font size: " + next);
    }

    public void resetFontSize() {
        ConfigManager cfg = ConfigManager.getInstance();
        cfg.set(ConfigManager.KEY_FONT_SIZE, String.valueOf(DEFAULT_FONT_SIZE));
        applyTerminalFont(cfg.get(ConfigManager.KEY_TERMINAL_FONT_FAMILY, ""), DEFAULT_FONT_SIZE);
        mainController.updateStatus("Font size: " + DEFAULT_FONT_SIZE);
    }

    private void setupTabContextMenu(Tab tab) {
        ContextMenu menu = new ContextMenu();
        MenuItem duplicate = new MenuItem(I18nManager.t("tab.duplicate"));
        MenuItem openSftp = new MenuItem(I18nManager.t("tab.open_sftp"));
        MenuItem close = new MenuItem(I18nManager.t("tab.close"));

        duplicate.setOnAction(e -> {
            ConnectionProfile profile = tabProfile.get(tab);
            if (profile != null) openTab(profile);
        });

        openSftp.setOnAction(e -> openSftpForTab(tab));

        close.setOnAction(e -> closeTab(tab));

        menu.getItems().addAll(duplicate, openSftp, close);
        tab.setContextMenu(menu);
    }

    public boolean openSftpForTab(Tab targetTab) {
        if (targetTab == null) return false;
        SshSession session = connectionManager.getSession(targetTab);
        if (session == null || !session.isConnected()) return false;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SftpBrowser.fxml"));
            Parent root = loader.load();
            SftpBrowserController controller = loader.getController();
            controller.setSession(session);

            String host = session.getProfile().getHost();
            Tab tab = new Tab("SFTP: " + host);
            tab.setContent(root);
            tab.setClosable(true);
            tab.setOnClosed(e -> controller.cleanup());
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
            return true;
        } catch (IOException e) {
            mainController.showError("Failed to open SFTP: " + e.getMessage());
            return false;
        }
    }

    private static void applyBackgroundStyle(Pane pane, String hex) {
        if (pane == null) return;
        pane.setStyle("-fx-background-color: " + hex + ";");
    }

    /**
     * Ctrl+wheel and Ctrl+plus/minus/0 zoom. Registered as event filters on the
     * widget's pane so they run before jeditermfx's own handlers forward the key
     * to the remote shell; consuming the event keeps the shortcut local.
     */
    private void installZoomHandlers(Pane pane) {
        if (pane == null) return;
        pane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown() || e.getDeltaY() == 0) return;
            adjustFontSize(e.getDeltaY() > 0 ? 1 : -1);
            e.consume();
        });
        pane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!e.isControlDown() || e.isAltDown() || e.isMetaDown()) return;
            switch (e.getCode()) {
                case EQUALS, PLUS, ADD -> { adjustFontSize(1); e.consume(); }
                case MINUS, SUBTRACT -> { adjustFontSize(-1); e.consume(); }
                case DIGIT0, NUMPAD0 -> { resetFontSize(); e.consume(); }
                default -> { }
            }
        });
    }

    /**
     * Open a SFTP tab for the currently-selected terminal session.
     */
    public boolean openSftpForCurrentTab() {
        return openSftpForTab(getCurrentTab());
    }
}
