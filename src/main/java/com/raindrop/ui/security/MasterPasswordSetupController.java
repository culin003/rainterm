package com.raindrop.ui.security;

import com.raindrop.core.TaskExecutor;
import com.raindrop.security.SecurityManager;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class MasterPasswordSetupController {
    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label currentPasswordLabel;
    @FXML private Label newPasswordLabel;
    @FXML private Label confirmPasswordLabel;
    @FXML private Label hintLabel;
    @FXML private Label hintWarningLabel;
    @FXML private PasswordField currentPasswordField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private TextField hintField;
    @FXML private Label statusLabel;
    @FXML private Button okButton;
    @FXML private Button cancelButton;

    /** True when acting as first-time setup (no current-password row); false = change-password. */
    private boolean setupMode = true;
    private Runnable onSuccessCallback;

    public void setSetupMode(boolean setup) {
        this.setupMode = setup;
        Platform.runLater(this::updateModeUi);
    }

    public void setOnSuccess(Runnable cb) { this.onSuccessCallback = cb; }

    @FXML
    public void initialize() {
        // Set labels with i18n
        newPasswordLabel.setText(I18nManager.t("master_password_setup.new_password"));
        confirmPasswordLabel.setText(I18nManager.t("master_password_setup.confirm_password"));
        hintLabel.setText(I18nManager.t("master_password_setup.hint"));
        hintWarningLabel.setText(I18nManager.t("master_password_setup.hint_warning"));
        descriptionLabel.setText(I18nManager.t("master_password_setup.description"));
        okButton.setText(I18nManager.t("common.ok"));
        cancelButton.setText(I18nManager.t("common.cancel"));

        Platform.runLater(this::updateModeUi);
    }

    private void updateModeUi() {
        if (setupMode) {
            titleLabel.setText(I18nManager.t("master_password_setup.title_new"));
            currentPasswordLabel.setVisible(false);
            currentPasswordLabel.setManaged(false);
            currentPasswordField.setVisible(false);
            currentPasswordField.setManaged(false);
        } else {
            titleLabel.setText(I18nManager.t("master_password_setup.title_change"));
            currentPasswordLabel.setText(I18nManager.t("master_password_setup.current_password"));
            currentPasswordLabel.setVisible(true);
            currentPasswordLabel.setManaged(true);
            currentPasswordField.setVisible(true);
            currentPasswordField.setManaged(true);
        }
    }

    @FXML
    private void onOk() {
        String p1 = passwordField.getText();
        String p2 = confirmField.getText();
        if (p1 == null || p1.length() < 4) {
            statusLabel.setText("Password must be at least 4 characters");  // TODO: i18n
            return;
        }
        if (!p1.equals(p2)) {
            statusLabel.setText("Passwords do not match");  // TODO: i18n
            return;
        }
        String hint = hintField.getText();
        statusLabel.setText("Working...");  // TODO: i18n
        okButton.setDisable(true);
        cancelButton.setDisable(true);
        if (setupMode) {
            runSetup(p1, hint);
        } else {
            String current = currentPasswordField.getText();
            if (current == null || current.isEmpty()) {
                statusLabel.setText("Current password required");  // TODO: i18n
                okButton.setDisable(false);
                cancelButton.setDisable(false);
                return;
            }
            runChange(current, p1, hint);
        }
    }

    private void runSetup(String password, String hint) {
        TaskExecutor.submit(() -> {
            try {
                SecurityManager.getInstance().completeSetup(password, hint);
                Platform.runLater(this::success);
            } catch (Exception e) {
                Platform.runLater(() -> fail(e.getMessage()));
            }
        });
    }

    private void runChange(String current, String next, String hint) {
        TaskExecutor.submit(() -> {
            try {
                SecurityManager.getInstance().changeMasterPassword(current, next, hint);
                Platform.runLater(this::success);
            } catch (Exception e) {
                Platform.runLater(() -> fail(e.getMessage()));
            }
        });
    }

    private void success() {
        if (onSuccessCallback != null) onSuccessCallback.run();
        close();
    }

    private void fail(String message) {
        statusLabel.setText(message == null ? "Operation failed" : message);
        okButton.setDisable(false);
        cancelButton.setDisable(false);
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        Stage stage = (Stage) okButton.getScene().getWindow();
        stage.close();
    }
}
