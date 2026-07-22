package com.raindrop.ui;

import com.raindrop.core.ConnectionManager;
import com.raindrop.core.SshSession;
import com.raindrop.core.TaskExecutor;
import com.raindrop.security.IdleWatchdog;
import com.raindrop.security.SecurityManager;
import com.raindrop.storage.ConnectionProfile;
import com.raindrop.ui.security.LockController;
import com.raindrop.ui.security.MasterPasswordSetupController;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.I18nManager;
import com.raindrop.util.ThemeManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;

public class MainController {
    private static final String KEY_SIDEBAR_VISIBLE = "sidebar_visible";
    private static final double DEFAULT_DIVIDER = 0.22;

    @FXML private TabPane tabPane;
    @FXML private Label statusLabel;
    @FXML private Label memoryLabel;
    @FXML private ProgressBar memoryBar;
    @FXML private SessionListPaneController sessionListController;
    @FXML private Node sessionList;
    @FXML private SplitPane mainSplit;
    @FXML private Button toggleSidebarButton;
    @FXML private Button lockButton;
    @FXML private Button newConnectionButton;
    @FXML private Button credentialsButton;
    @FXML private Button sftpButton;
    @FXML private Button settingsButton;
    @FXML private Button disconnectAllButton;

    private final ConnectionManager connectionManager = new ConnectionManager();
    private TabManager tabManager;
    private Timeline memoryMonitor;
    private double lastDivider = DEFAULT_DIVIDER;
    private boolean sidebarVisible = true;

    // Lock overlay state
    private StackPane sceneRoot;
    private Parent lockOverlay;
    private LockController lockController;
    private Stage primaryStageRef;

    // Memory-monitor caches so we skip redundant setText/setStyle when the
    // displayed values haven't actually changed. The FX Application Thread
    // is single-threaded so no synchronization is needed here.
    private long lastUsedMb = -1;
    private String lastMemoryStyleTier = "";

    @FXML
    public void initialize() {
        tabManager = new TabManager(tabPane, connectionManager, this);
        if (sessionListController != null) {
            sessionListController.setMainController(this);
            sessionListController.refresh();
        }
        statusLabel.setText(I18nManager.t("main.status_ready", "count", String.valueOf(tabManager.getActiveTabCount())));

        // Set toolbar button texts with i18n
        newConnectionButton.setText(I18nManager.t("main.new_connection"));
        credentialsButton.setText(I18nManager.t("main.manage_credentials"));
        sftpButton.setText(I18nManager.t("main.sftp"));
        settingsButton.setText(I18nManager.t("main.settings"));
        disconnectAllButton.setText(I18nManager.t("main.disconnect_all"));
        lockButton.setText(I18nManager.t("main.lock"));

        // Restore prior sidebar state (default visible).
        boolean shouldShow = ConfigManager.getInstance().getBoolean(KEY_SIDEBAR_VISIBLE, true);
        updateToggleButtonGraphic(shouldShow);
        if (!shouldShow) {
            // Apply after layout so removal doesn't fight the initial divider position.
            Platform.runLater(this::hideSidebar);
        }

        startMemoryMonitor();

        // Register global accelerators once the scene is attached. Doing it here
        // (rather than in FXML) keeps the shortcuts near the handlers they trigger.
        Platform.runLater(this::installAccelerators);
    }

    private void installAccelerators() {
        Scene scene = tabPane.getScene();
        if (scene == null) return;
        var acc = scene.getAccelerators();
        // SHORTCUT_DOWN maps to Cmd on macOS, Ctrl elsewhere.
        acc.put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN),
            this::onNewConnection);
        acc.put(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN),
            this::closeCurrentTab);
        acc.put(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
            () -> cycleTabs(1));
        acc.put(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
            () -> cycleTabs(-1));
        acc.put(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            this::onDisconnectAll);
        acc.put(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
            this::onOpenSettings);
        acc.put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN),
            this::onLock);
        installActivityFilter(scene);
        installLockedKeySuppressor(scene);
    }

    /**
     * Reset lastActivity on any real user input reaching the scene. Terminal PTY
     * output arrives via {@code Platform.runLater} and does NOT traverse Scene
     * event dispatch, so it naturally does not count.
     */
    private void installActivityFilter(Scene scene) {
        scene.addEventFilter(Event.ANY, ev -> {
            var t = ev.getEventType();
            if (t == MouseEvent.MOUSE_MOVED || t == MouseEvent.MOUSE_PRESSED
             || t == KeyEvent.KEY_PRESSED   || t == ScrollEvent.SCROLL) {
                IdleWatchdog.get().markActivity();
            }
        });
    }

    /**
     * While locked, consume any shortcut (Ctrl/Cmd) key press before accelerators
     * see it. Combined with {@code mainRoot.setDisable(true)} this ensures the
     * user cannot trigger app actions during the locked state.
     */
    private void installLockedKeySuppressor(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (SecurityManager.getInstance().isLocked() && ev.isShortcutDown()) {
                ev.consume();
            }
        });
    }

    @FXML
    public void onLock() {
        SecurityManager.getInstance().lock();
    }

    private void closeCurrentTab() {
        Tab current = tabPane.getSelectionModel().getSelectedItem();
        if (current != null && current.isClosable()) {
            tabManager.closeTab(current);
        }
    }

    private void cycleTabs(int direction) {
        int size = tabPane.getTabs().size();
        if (size <= 1) return;
        int idx = tabPane.getSelectionModel().getSelectedIndex();
        int next = ((idx + direction) % size + size) % size;
        tabPane.getSelectionModel().select(next);
    }

    private void startMemoryMonitor() {
        updateMemoryStats();
        // 3s cadence (was 2s): memory usage rarely changes user-perceptibly
        // faster than that, and this saves ~33% of the Timeline wakeups.
        memoryMonitor = new Timeline(new KeyFrame(Duration.seconds(3), e -> updateMemoryStats()));
        memoryMonitor.setCycleCount(Animation.INDEFINITE);
        memoryMonitor.play();
    }

    private void updateMemoryStats() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        long usedMb = used / (1024L * 1024L);
        double ratio = max > 0 ? (double) used / max : 0.0;

        // Skip setText / setProgress if the coarse display didn't change.
        // JavaFX still applies CSS on every setText even when the value is
        // identical; skipping here keeps the label and progress bar quiet.
        if (usedMb != lastUsedMb) {
            long maxMb = max / (1024L * 1024L);
            memoryLabel.setText(I18nManager.t("main.mem_usage",
                "used", String.valueOf(usedMb),
                "max", String.valueOf(maxMb),
                "percent", String.format("%.0f", ratio * 100)));
            memoryBar.setProgress(ratio);
            lastUsedMb = usedMb;
        }

        String tier = ratio > 0.85 ? "red" : ratio > 0.65 ? "orange" : "green";
        if (!tier.equals(lastMemoryStyleTier)) {
            String style = switch (tier) {
                case "red" -> "-fx-accent: #e74c3c;";
                case "orange" -> "-fx-accent: #f39c12;";
                default -> "-fx-accent: #2ecc71;";
            };
            memoryBar.setStyle(style);
            lastMemoryStyleTier = tier;
        }
    }

    public void stopMemoryMonitor() {
        if (memoryMonitor != null) {
            memoryMonitor.stop();
            memoryMonitor = null;
        }
    }

    @FXML
    private void onToggleSidebar() {
        if (sidebarVisible) hideSidebar(); else showSidebar();
    }

    private void hideSidebar() {
        if (!sidebarVisible || mainSplit == null || sessionList == null) return;
        if (!mainSplit.getDividers().isEmpty()) {
            lastDivider = mainSplit.getDividers().get(0).getPosition();
        }
        sidebarVisible = false;
        updateToggleButtonGraphic(false);
        ConfigManager.getInstance().set(KEY_SIDEBAR_VISIBLE, "false");
        // Defer the items mutation to the next FX pulse. Doing it synchronously from
        // a button handler collides with SplitPane's internal divider animation and
        // throws a KeyFrame→Timeline ClassCastException (a JavaFX SplitPane quirk).
        Platform.runLater(() -> {
            if (mainSplit.getItems().size() > 1) {
                Node tabParent = mainSplit.getItems().get(1);
                mainSplit.getItems().setAll(tabParent);
            }
        });
    }

    private void showSidebar() {
        if (sidebarVisible || mainSplit == null || sessionList == null) return;
        sidebarVisible = true;
        updateToggleButtonGraphic(true);
        ConfigManager.getInstance().set(KEY_SIDEBAR_VISIBLE, "true");
        Platform.runLater(() -> {
            if (mainSplit.getItems().contains(sessionList)) return;
            Node tabParent = mainSplit.getItems().isEmpty() ? null : mainSplit.getItems().get(0);
            if (tabParent == null) {
                mainSplit.getItems().setAll(sessionList);
            } else {
                mainSplit.getItems().setAll(sessionList, tabParent);
            }
            // Divider restoration must land on yet another pulse — after the items
            // update has been laid out — otherwise the same CCE fires here.
            Platform.runLater(() -> {
                if (!mainSplit.getDividers().isEmpty()) {
                    mainSplit.setDividerPositions(lastDivider);
                }
            });
        });
    }

    private void updateToggleButtonGraphic(boolean visible) {
        if (toggleSidebarButton == null) return;
        // Chevron points in the direction the click will move the panel:
        //   sidebar visible → arrow-left (collapse it away)
        //   sidebar hidden  → arrow-right (bring it back)
        FontIcon icon = new FontIcon(visible ? FontAwesomeSolid.ANGLE_LEFT : FontAwesomeSolid.ANGLE_RIGHT);
        icon.setIconSize(14);
        toggleSidebarButton.setText("");
        toggleSidebarButton.setGraphic(icon);
        toggleSidebarButton.setTooltip(new Tooltip(visible
            ? I18nManager.t("main.sidebar_hide")
            : I18nManager.t("main.sidebar_show")));
    }

    @FXML
    private void onNewConnection() {
        openNewConnectionDialog(null);
    }

    public void openNewConnectionDialog(ConnectionProfile existing) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ConnectionDialog.fxml"));
            Parent root = loader.load();
            ConnectionDialogController controller = loader.getController();
            controller.setMainController(this);
            if (existing != null) {
                controller.setProfile(existing);
            }
            Scene scene = new Scene(root, 560, 520);
            ThemeManager.apply(scene);
            Stage stage = new Stage();
            stage.setTitle(existing == null
                ? I18nManager.t("connection_dialog.title_new")
                : I18nManager.t("connection_dialog.title_edit"));
            stage.setScene(scene);
            showAsModalDialog(stage);
        } catch (IOException e) {
            showError(I18nManager.t("errors.load_dialog_failed", "message", e.getMessage()));
        }
    }

    @FXML
    private void onManageCredentials() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/CredentialDialog.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root, 700, 450);
            ThemeManager.apply(scene);
            Stage stage = new Stage();
            stage.setTitle(I18nManager.t("credential_dialog.title"));
            stage.setScene(scene);
            showAsModalDialog(stage);
        } catch (IOException e) {
            showError(I18nManager.t("errors.load_dialog_failed", "message", e.getMessage()));
        }
    }

    @FXML
    private void onDisconnectAll() {
        tabManager.closeAllTabs();
        connectionManager.disconnectAll();
        updateStatus(I18nManager.t("status.all_disconnected"));
    }

    @FXML
    private void onOpenSftp() {
        if (!tabManager.openSftpForCurrentTab()) {
            showError(I18nManager.t("errors.sftp_no_session"));
        }
    }

    @FXML
    private void onOpenSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
            Parent root = loader.load();
            SettingsViewController controller = loader.getController();
            controller.setMainController(this);
            Scene scene = new Scene(root, 480, 360);
            ThemeManager.apply(scene);
            Stage stage = new Stage();
            stage.setTitle(I18nManager.t("settings.title"));
            stage.setScene(scene);
            showAsModalDialog(stage);
        } catch (IOException e) {
            showError(I18nManager.t("errors.load_dialog_failed", "message", e.getMessage()));
        }
    }

    private Window ownerWindow() {
        Scene scene = tabPane == null ? null : tabPane.getScene();
        return scene == null ? null : scene.getWindow();
    }

    /**
     * Show a child stage as a stand-alone top-level window WITHOUT a parent
     * relationship. Reason: KDE/KWin (and some other Linux WMs) unconditionally
     * un-maximize the owner window whenever a transient (owned) top-level maps
     * for the first time — even for `WINDOW_MODAL`. There is no fix for that
     * from JavaFX side while `initOwner` is set.
     *
     * We simulate modal behaviour manually:
     *   - `mainRoot.setDisable(true)` blocks input to the primary content
     *     while the dialog is up.
     *   - `stage.setAlwaysOnTop(true)` keeps the dialog above the main
     *     window so it never gets buried when the user clicks the main
     *     window's title bar (input is disabled but focus can still shift).
     *   - `StageStyle.UTILITY` marks the window as a tool window so most
     *     WMs (KWin included) don't put a second entry in the task list
     *     and don't include it in Alt-Tab.
     *   - When the dialog hides, re-enable the main content.
     *
     * Since the dialog is NOT a transient/owned window the WM doesn't touch
     * the main Stage's maximized state at all — the maximize flag stays true
     * and the geometry stays fullscreen.
     */
    private void showAsModalDialog(Stage stage) {
        Window owner = ownerWindow();
        javafx.scene.Node mainRoot = owner != null && owner.getScene() != null
            ? owner.getScene().getRoot() : null;

        stage.initStyle(StageStyle.UTILITY);
        stage.setAlwaysOnTop(true);

        if (mainRoot != null) mainRoot.setDisable(true);
        stage.setOnHidden(e -> {
            if (mainRoot != null) mainRoot.setDisable(false);
        });

        // sizeToScene() respects the FXML root's prefWidth/prefHeight and does
        // proper layout calculation. Must call before showing and before centering.
        stage.sizeToScene();

        javafx.geometry.Rectangle2D b = ownerVisualBounds(owner);
        stage.setX(b.getMinX() + (b.getWidth() - stage.getWidth()) / 2);
        stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);

        // Re-center once after peer is mapped, defensively.
        stage.setOnShown(e -> {
            double w = stage.getWidth();
            double h = stage.getHeight();
            javafx.geometry.Rectangle2D rb = ownerVisualBounds(owner);
            stage.setX(rb.getMinX() + (rb.getWidth() - w) / 2);
            stage.setY(rb.getMinY() + (rb.getHeight() - h) / 2);
        });

        stage.show();
        stage.requestFocus();
    }

    /**
     * Centering target: screen visual bounds if the owner is maximized (or
     * lies that it is), otherwise the owner's own rectangle. Picks whichever
     * screen the owner's origin lives on; falls back to primary screen.
     */
    private javafx.geometry.Rectangle2D ownerVisualBounds(Window owner) {
        if (owner instanceof Stage os && os.isMaximized()) {
            return javafx.stage.Screen.getScreensForRectangle(
                    os.getX(), os.getY(), 8, 8).stream().findFirst()
                .orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
        }
        if (owner != null) {
            return new javafx.geometry.Rectangle2D(
                owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
        }
        return javafx.stage.Screen.getPrimary().getVisualBounds();
    }

    public void openConnection(ConnectionProfile profile) {
        tabManager.openTab(profile);
        updateStatus("Connecting to " + profile.getHost() + "...");  // TODO: i18n
    }

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");  // TODO: i18n
            alert.setHeaderText(null);
            alert.setContentText(message);
            ThemeManager.apply(alert);
            // Do NOT initOwner: transient/owned top-levels cause KWin to
            // un-maximize the primary Stage. Center manually instead.
            Stage as = (Stage) alert.getDialogPane().getScene().getWindow();
            as.setAlwaysOnTop(true);
            Window owner = ownerWindow();
            javafx.geometry.Rectangle2D b = ownerVisualBounds(owner);
            alert.setOnShown(e -> {
                double w = as.getWidth();
                double h = as.getHeight();
                as.setX(b.getMinX() + (b.getWidth() - w) / 2);
                as.setY(b.getMinY() + (b.getHeight() - h) / 2);
            });
            alert.showAndWait();
        });
    }

    public TabManager getTabManager() {
        return tabManager;
    }

    /**
     * Re-apply the application stylesheet for {@code themeKey} to whichever Scene hosts
     * our main tabPane. Called from SettingsViewController after the user picks a theme.
     *
     * <p>Also walks every open child window (open dialogs, alerts) and re-applies the
     * same stylesheet so the whole app follows the theme live, not just the main scene.
     */
    public void applyTheme(String themeKey) {
        // The `themeKey` parameter reflects the user's selection; ConfigManager
        // was already updated by SettingsViewController before this call, so
        // ThemeManager.currentStylesheetUrl() resolves to the same URL.
        ThemeManager.applyToAllWindows();
        if (tabManager != null) {
            tabManager.applyTerminalTheme(themeKey);
        }
        updateStatus("Theme: " + themeKey);
    }

    public void refreshSessionList() {
        if (sessionListController != null) {
            sessionListController.refresh();
        }
    }

    /**
     * Called by RaindropApp after the Stage is shown. Loads the lock overlay,
     * wires the SecurityManager listener, and either runs the setup dialog
     * (uninitialized) or shows the lock overlay (locked).
     */
    public void presentSecurityFlow(Stage primaryStage, StackPane rootStack) {
        this.primaryStageRef = primaryStage;
        this.sceneRoot = rootStack;
        SecurityManager sm = SecurityManager.getInstance();
        sm.lockedProperty().addListener((obs, wasLocked, nowLocked) -> onLockStateChanged(nowLocked));
        if (sm.isUninitialized()) {
            openSetupDialog(primaryStage);
        } else {
            showLockOverlay();
        }
        // Idle watchdog is always started; it self-noops while locked or when timeout=0.
        IdleWatchdog.get().start();
    }

    private void onLockStateChanged(boolean nowLocked) {
        if (nowLocked) {
            showLockOverlay();
        } else {
            hideLockOverlay();
        }
    }

    private void ensureLockOverlayLoaded() {
        if (lockOverlay != null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/LockView.fxml"));
            lockOverlay = loader.load();
            lockController = loader.getController();
            lockController.setOnUnlocked(() -> {
                // No-op: SecurityManager.lockedProperty will fire hideLockOverlay.
            });
            lockController.setOnNeedSetup(() -> {
                hideLockOverlay();
                if (primaryStageRef != null) {
                    openSetupDialog(primaryStageRef);
                }
            });
        } catch (IOException e) {
            showError("Failed to load lock overlay: " + e.getMessage());
        }
    }

    private void showLockOverlay() {
        if (sceneRoot == null) return;
        ensureLockOverlayLoaded();
        if (lockOverlay == null) return;
        if (!sceneRoot.getChildren().contains(lockOverlay)) {
            sceneRoot.getChildren().add(lockOverlay);
        }
        lockOverlay.setVisible(true);
        // Belt-and-suspenders: disable the underlying main content so focus/accelerators
        // that somehow bypass the overlay still cannot fire.
        Node main = sceneRoot.getChildren().isEmpty() ? null : sceneRoot.getChildren().get(0);
        if (main != null) main.setDisable(true);
        if (lockController != null) lockController.focusPasswordField();
    }

    private void hideLockOverlay() {
        if (sceneRoot == null) return;
        if (lockOverlay != null) lockOverlay.setVisible(false);
        Node main = sceneRoot.getChildren().isEmpty() ? null : sceneRoot.getChildren().get(0);
        if (main != null) main.setDisable(false);
        if (tabPane != null && !tabPane.getTabs().isEmpty()) {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getContent() != null) {
                sel.getContent().requestFocus();
            }
        }
    }

    private void openSetupDialog(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MasterPasswordSetupView.fxml"));
            Parent root = loader.load();
            MasterPasswordSetupController controller = loader.getController();
            controller.setSetupMode(true);
            Scene scene = new Scene(root);
            ThemeManager.apply(scene);
            Stage stage = new Stage();
            stage.setTitle("Set Master Password");
            stage.setScene(scene);
            stage.setOnCloseRequest(e -> {
                // Block dismissal until setup is complete.
                if (SecurityManager.getInstance().isUninitialized()) {
                    e.consume();
                }
            });
            controller.setOnSuccess(() -> {
                // On success SecurityManager transitions to UNLOCKED and lockedProperty fires.
                // Dialog closes itself; nothing more to do.
            });
            showAsModalDialog(stage);
        } catch (IOException e) {
            showError("Failed to load setup dialog: " + e.getMessage());
        }
    }

    /**
     * Public accessor for Settings → Change Master Password.
     */
    public void openChangeMasterPasswordDialog() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MasterPasswordSetupView.fxml"));
            Parent root = loader.load();
            MasterPasswordSetupController controller = loader.getController();
            controller.setSetupMode(false);
            Scene scene = new Scene(root);
            ThemeManager.apply(scene);
            Stage stage = new Stage();
            stage.setTitle("Change Master Password");
            stage.setScene(scene);
            showAsModalDialog(stage);
        } catch (IOException e) {
            showError("Failed to load change-password dialog: " + e.getMessage());
        }
    }
}
