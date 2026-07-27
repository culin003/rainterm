package com.raindrop.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Optional;

/**
 * Utility for showing independent popup windows.
 * <p>
 * Every popup is a standalone top-level {@link Stage} with no owner.
 * New dialogs always appear centered on top of the last opened dialog.
 * If no dialog has been opened yet, the dialog centers on the primary screen.
 */
public final class DialogUtil {

    private DialogUtil() {}

    /** Track the last dialog position so new dialogs stack on top. */
    private static double lastX = -1;
    private static double lastY = -1;
    private static double lastW = -1;
    private static double lastH = -1;

    /**
     * Show a fresh {@link Stage} (created by us) as an independent non-blocking
     * popup. Safe to call {@code initStyle(UTILITY)} here because the Stage has
     * never been mapped yet.
     */
    public static void showDialog(Stage stage) {
        stage.initStyle(StageStyle.UTILITY);
        stage.setAlwaysOnTop(true);
        stage.sizeToScene();
        stage.setOnShown(e -> {
            // Re-center after the peer is mapped and dimensions are final.
            centerOnTarget(stage);
            updateLastPosition(stage);
        });
        stage.show();
        stage.requestFocus();
        centerOnTarget(stage);
    }

    /**
     * Show a simple message dialog (error / info / warning) as an independent
     * popup window. Uses the same reliable approach as the settings dialog:
     * a plain Stage with UTILITY style, no owner, no modality.
     */
    public static void showMessage(String title, String message) {
        Label label = new Label(message);
        label.setWrapText(true);
        label.setMaxWidth(400);

        Button okButton = new Button("OK");
        okButton.setDefaultButton(true);

        VBox layout = new VBox(15, label, okButton);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(20));

        Scene scene = new Scene(layout, 440, 160);
        ThemeManager.apply(scene);

        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(scene);

        okButton.setOnAction(e -> stage.close());

        showDialog(stage);
    }

    /**
     * Show a {@link Dialog} (Alert / TextInputDialog / ...) as an independent
     * blocking popup. Returns the dialog result, or empty if cancelled/closed.
     */
    public static <T> Optional<T> showBlockingDialog(Dialog<T> dialog) {
        dialog.initModality(Modality.NONE);
        ThemeManager.apply(dialog.getDialogPane());
        Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
        stage.setAlwaysOnTop(true);
        stage.setOnShown(e -> {
            stage.sizeToScene();
            centerOnTarget(stage);
            updateLastPosition(stage);
        });
        return dialog.showAndWait();
    }

    /** Center the stage on the target area (last dialog or screen). */
    private static void centerOnTarget(Stage stage) {
        double sw = stage.getWidth();
        double sh = stage.getHeight();
        if (Double.isNaN(sw) || sw <= 0) sw = 0;
        if (Double.isNaN(sh) || sh <= 0) sh = 0;
        Rectangle2D target = lastW > 0
            ? new Rectangle2D(lastX, lastY, lastW, lastH)
            : Screen.getPrimary().getVisualBounds();
        stage.setX(target.getMinX() + (target.getWidth() - sw) / 2);
        stage.setY(target.getMinY() + (target.getHeight() - sh) / 2);
    }

    private static void updateLastPosition(Stage stage) {
        lastX = stage.getX();
        lastY = stage.getY();
        lastW = stage.getWidth();
        lastH = stage.getHeight();
    }

    /** Reset tracked position (e.g., when the main window moves). */
    public static void resetPosition() {
        lastX = -1;
        lastY = -1;
        lastW = -1;
        lastH = -1;
    }
}
