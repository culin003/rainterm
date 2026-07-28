package com.raindrop;

import com.raindrop.security.IdleWatchdog;
import com.raindrop.security.SecurityManager;
import com.raindrop.storage.DatabaseManager;
import com.raindrop.ui.MainController;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.FontManager;
import com.raindrop.util.ThemeManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;

public class RaindropApp extends Application {
    private static MainController mainController;

    @Override
    public void start(Stage primaryStage) throws IOException {
        FontManager.loadBundledFonts();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        mainController = loader.getController();

        ConfigManager cfg = ConfigManager.getInstance();
        int w = 900;
        int h = 700;

        // Wrap the BorderPane in a StackPane so we can overlay a lock screen
        // on top without detaching the main content (which would tear down the
        // JediTermFxWidget SwingNode peers).
        StackPane sceneRoot = new StackPane(root);

        Scene scene = new Scene(sceneRoot, w, h);
        ThemeManager.apply(scene);
        primaryStage.setTitle("Raindrop SSH Manager");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            if (mainController != null) {
                mainController.stopMemoryMonitor();
                if (mainController.getTabManager() != null) {
                    mainController.getTabManager().closeAllTabs();
                }
            }
            IdleWatchdog.get().stop();
        });

        SecurityManager.getInstance().bootstrap();
        primaryStage.show();
        Platform.runLater(() -> mainController.presentSecurityFlow(primaryStage, sceneRoot));
    }

    @Override
    public void stop() {
        // Called by JavaFX after the last stage is closed. Release everything not
        // already released by the close-request handler so the JVM can exit cleanly.
        try { IdleWatchdog.get().stop(); } catch (Exception ignored) {}
        try { if (mainController != null) mainController.stopMemoryMonitor(); } catch (Exception ignored) {}
        try { if (mainController != null && mainController.getTabManager() != null) mainController.getTabManager().closeAllTabs(); } catch (Exception ignored) {}
        try { com.raindrop.core.TaskExecutor.shutdown(); } catch (Exception ignored) {}
        try { DatabaseManager.close(); } catch (Exception ignored) {}
    }

    public static MainController getMainController() {
        return mainController;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
