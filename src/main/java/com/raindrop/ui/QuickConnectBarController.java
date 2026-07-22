package com.raindrop.ui;

import com.raindrop.RaindropApp;
import com.raindrop.storage.ConnectionProfile;
import com.raindrop.util.CryptoUtil;
import com.raindrop.util.I18nManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

/**
 * Embedded quick-connect bar. Uses the currently-typed values as a transient,
 * unsaved connection profile and opens a terminal tab. Nothing is written to
 * the database — the profile only lives for the duration of the tab.
 */
public class QuickConnectBarController {
    @FXML private Label quickConnectLabel;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField userField;
    @FXML private PasswordField passField;
    @FXML private Button connectButton;

    @FXML
    public void initialize() {
        quickConnectLabel.setText(I18nManager.t("quick_connect.title"));
        connectButton.setText(I18nManager.t("common.connect"));

        // Set prompt texts with i18n
        hostField.setPromptText(I18nManager.t("quick_connect.host_placeholder"));
        userField.setPromptText(I18nManager.t("quick_connect.user_placeholder"));
        passField.setPromptText(I18nManager.t("quick_connect.password_placeholder"));

        // Pressing Enter in any field triggers connect for keyboard-friendly use.
        hostField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) onConnect(); });
        portField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) onConnect(); });
        userField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) onConnect(); });
        passField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) onConnect(); });
    }

    @FXML
    private void onConnect() {
        String host = hostField.getText();
        String user = userField.getText();
        if (host == null || host.isEmpty() || user == null || user.isEmpty()) return;

        int port;
        try {
            port = Integer.parseInt(portField.getText());
        } catch (NumberFormatException e) {
            port = 22;
        }

        ConnectionProfile profile = new ConnectionProfile(host + ":" + port, host, port, user);
        String pass = passField.getText();
        if (pass != null && !pass.isEmpty()) {
            profile.setPassword(CryptoUtil.encrypt(pass));
        }
        profile.setAuthType("password_inline");

        MainController mainController = RaindropApp.getMainController();
        if (mainController != null) {
            mainController.openConnection(profile);
            passField.clear();
        }
    }
}
