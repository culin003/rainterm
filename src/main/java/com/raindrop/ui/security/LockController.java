package com.raindrop.ui.security;

import com.raindrop.core.TaskExecutor;
import com.raindrop.security.SecurityManager;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

public class LockController {
    @FXML private Label titleLabel;
    @FXML private PasswordField passwordField;
    @FXML private Label hintLabel;
    @FXML private Label errorLabel;
    @FXML private Button unlockButton;
    @FXML private Hyperlink forgotLink;

    private Runnable onUnlockedCallback;
    private Runnable onNeedSetupCallback;

    public void setOnUnlocked(Runnable callback) {
        this.onUnlockedCallback = callback;
    }

    public void setOnNeedSetup(Runnable callback) {
        this.onNeedSetupCallback = callback;
    }

    @FXML
    public void initialize() {
        titleLabel.setText(I18nManager.t("lock.title"));
        passwordField.setPromptText(I18nManager.t("lock.password_placeholder"));

        String hint = ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_HINT);
        if (hint != null && !hint.isEmpty()) {
            hintLabel.setText(I18nManager.t("lock.hint_prefix") + hint);
        } else {
            hintLabel.setText("");
        }
        unlockButton.setText(I18nManager.t("lock.unlock"));
        forgotLink.setText(I18nManager.t("lock.forgot_link"));
    }

    public void focusPasswordField() {
        Platform.runLater(() -> {
            passwordField.clear();
            errorLabel.setText("");
            passwordField.requestFocus();
        });
    }

    @FXML
    private void onUnlock() {
        String pwd = passwordField.getText();
        if (pwd == null || pwd.isEmpty()) {
            errorLabel.setText(I18nManager.t("lock.password_required"));
            return;
        }
        unlockButton.setDisable(true);
        errorLabel.setText(I18nManager.t("lock.verifying"));
        TaskExecutor.submit(() -> {
            boolean ok;
            try {
                ok = SecurityManager.getInstance().unlock(pwd);
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            Platform.runLater(() -> {
                unlockButton.setDisable(false);
                if (success) {
                    passwordField.clear();
                    errorLabel.setText("");
                    if (onUnlockedCallback != null) onUnlockedCallback.run();
                } else {
                    passwordField.clear();
                    errorLabel.setText(I18nManager.t("lock.wrong_password"));
                    passwordField.requestFocus();
                }
            });
        });
    }

    @FXML
    private void onForgot() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DestructiveResetView.fxml"));
            Parent root = loader.load();
            DestructiveResetController resetController = loader.getController();
            resetController.setOnResetCompleted(onNeedSetupCallback);
            Scene scene = new Scene(root);
            com.raindrop.util.ThemeManager.apply(scene);
            Stage stage = new Stage();
            stage.initStyle(StageStyle.UTILITY);
            stage.setAlwaysOnTop(true);
            stage.setTitle(I18nManager.t("lock.load_reset_title"));
            stage.setScene(scene);
            com.raindrop.util.DialogUtil.showDialog(stage);
        } catch (IOException e) {
            errorLabel.setText("Failed to load reset dialog: " + e.getMessage());  // TODO: i18n
        }
    }
}
