package com.raindrop.ui.security;

import com.raindrop.core.TaskExecutor;
import com.raindrop.security.SecurityManager;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class DestructiveResetController {
    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private TextField confirmField;
    @FXML private Label statusLabel;
    @FXML private Button confirmButton;
    @FXML private Button cancelButton;

    private Runnable onResetCompletedCallback;

    public void setOnResetCompleted(Runnable callback) {
        this.onResetCompletedCallback = callback;
    }

    @FXML
    public void initialize() {
        titleLabel.setText(I18nManager.t("destructive_reset.title"));
        descriptionLabel.setText(I18nManager.t("destructive_reset.description"));
        confirmField.setPromptText(I18nManager.t("destructive_reset.confirm_placeholder"));
        confirmButton.setText(I18nManager.t("destructive_reset.confirm_button"));
        cancelButton.setText(I18nManager.t("common.cancel"));

        confirmButton.setDisable(true);
        confirmField.textProperty().addListener((obs, o, n) ->
            confirmButton.setDisable(!"RESET".equals(n)));
    }

    @FXML
    private void onConfirm() {
        if (!"RESET".equals(confirmField.getText())) return;
        statusLabel.setText("Resetting...");
        confirmButton.setDisable(true);
        TaskExecutor.submit(() -> {
            try {
                SecurityManager.getInstance().destructiveReset();
                Platform.runLater(() -> {
                    close();
                    if (onResetCompletedCallback != null) {
                        onResetCompletedCallback.run();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Reset failed: " + e.getMessage());
                    confirmButton.setDisable(false);
                });
            }
        });
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        Stage stage = (Stage) confirmField.getScene().getWindow();
        stage.close();
    }
}
