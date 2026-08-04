package com.raindrop.ui;

import com.raindrop.credential.CredentialEntry;
import com.raindrop.credential.CredentialManager;
import com.raindrop.credential.KeyImporter;
import com.raindrop.util.CryptoUtil;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Controller for credential management dialog.
 */
public class CredentialDialogController {
    @FXML private ListView<CredentialEntry> credentialList;
    @FXML private TextField nameField;
    @FXML private ComboBox<String> typeComboBox;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField keyPathField;
    @FXML private PasswordField keyPassField;

    @FXML private Button newButton;
    @FXML private Button deleteButton;
    @FXML private Button saveButton;
    @FXML private Button closeButton;
    @FXML private Button browseButton;
    @FXML private Label nameLabel;
    @FXML private Label typeLabel;
    @FXML private Label usernameLabel;
    @FXML private Label passwordLabel;
    @FXML private Label keyPathLabel;
    @FXML private Label keyPassLabel;

    private final CredentialManager credentialManager = new CredentialManager();
    private CredentialEntry selectedEntry;

    @FXML
    public void initialize() {
        // Set all labels with i18n
        nameLabel.setText(I18nManager.t("credential_dialog.name"));
        typeLabel.setText(I18nManager.t("credential_dialog.type"));
        usernameLabel.setText(I18nManager.t("credential_dialog.username"));
        passwordLabel.setText(I18nManager.t("credential_dialog.password"));
        keyPathLabel.setText(I18nManager.t("credential_dialog.key_path"));
        keyPassLabel.setText(I18nManager.t("credential_dialog.key_passphrase"));
        newButton.setText(I18nManager.t("common.new"));
        deleteButton.setText(I18nManager.t("common.delete"));
        saveButton.setText(I18nManager.t("common.save"));
        closeButton.setText(I18nManager.t("common.close"));
        browseButton.setText(I18nManager.t("common.browse"));

        typeComboBox.setItems(FXCollections.observableArrayList("password", "key"));
        typeComboBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String type) {
                if ("password".equals(type)) return I18nManager.t("credential_dialog.type_password");
                if ("key".equals(type)) return I18nManager.t("credential_dialog.type_key");
                return type;
            }
            @Override public String fromString(String s) { return s; }
        });
        typeComboBox.getSelectionModel().selectFirst();

        credentialList.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> selectCredential(newVal));

        loadCredentials();
    }

    private void loadCredentials() {
        try {
            List<CredentialEntry> credentials = credentialManager.findAll();
            credentialList.setItems(FXCollections.observableArrayList(credentials));
        } catch (Exception e) {
            showError(I18nManager.t("errors.load_dialog_failed", "message", e.getMessage()));
        }
    }

    private void selectCredential(CredentialEntry entry) {
        this.selectedEntry = entry;
        if (entry != null) {
            nameField.setText(entry.getName());
            typeComboBox.getSelectionModel().select(entry.getType());
            usernameField.setText(entry.getUsername());
            if ("password".equals(entry.getType())) {
                passwordField.setText(entry.getPassword() != null ?
                    CryptoUtil.decrypt(entry.getPassword()) : "");
                keyPathField.clear();
                keyPassField.clear();
            } else {
                passwordField.clear();
                keyPathField.setText(entry.getKeyPath());
                keyPassField.setText(entry.getKeyPass() != null ?
                    CryptoUtil.decrypt(entry.getKeyPass()) : "");
            }
        }
    }

    @FXML
    private void onNew() {
        CredentialEntry newEntry = new CredentialEntry();
        newEntry.setName(I18nManager.t("credential_dialog.new"));
        newEntry.setType("password");
        newEntry.setUsername("");
        credentialList.getItems().add(newEntry);
        // Use Platform.runLater to ensure selection fires after layout pulse.
        // Also scroll to the new item so user sees it.
        Platform.runLater(() -> {
            credentialList.getSelectionModel().select(newEntry);
            credentialList.scrollTo(newEntry);
        });
    }

    @FXML
    private void onSave() {
        String name = nameField.getText();
        String type = typeComboBox.getValue();
        String username = usernameField.getText();

        if (name.isEmpty() || username.isEmpty()) {
            showError(I18nManager.t("credential_dialog.error_name_required"));
            return;
        }

        // If no selection, this is a new credential being saved directly
        CredentialEntry entry = selectedEntry;
        boolean isNew = false;
        if (entry == null) {
            entry = new CredentialEntry();
            isNew = true;
        }

        entry.setName(name);
        entry.setType(type);
        entry.setUsername(username);

        if ("password".equals(type)) {
            entry.setPassword(CryptoUtil.encrypt(passwordField.getText()));
            entry.setKeyData(null);
            entry.setKeyPath(null);
            entry.setKeyPass(null);
        } else {
            entry.setPassword(null);
            String keyPath = keyPathField.getText();
            if (!keyPath.isEmpty()) {
                try {
                    String keyContent = java.nio.file.Files.readString(java.nio.file.Path.of(keyPath));
                    entry.setKeyData(CryptoUtil.encrypt(keyContent));
                    entry.setKeyPath(keyPath);
                } catch (Exception e) {
                    showError(I18nManager.t("errors.load_dialog_failed", "message", e.getMessage()));
                    return;
                }
            }
            String keyPass = keyPassField.getText();
            if (keyPass != null && !keyPass.isEmpty()) {
                entry.setKeyPass(CryptoUtil.encrypt(keyPass));
            }
        }

        try {
            if (entry.getId() > 0) {
                credentialManager.update(entry);
            } else {
                long id = credentialManager.save(entry);
                entry.setId(id);
            }
            selectedEntry = entry;
            if (isNew) {
                // For newly-created entries (not in list yet), add and select.
                if (!credentialList.getItems().contains(entry)) {
                    credentialList.getItems().add(entry);
                }
            }
            loadCredentials();
            // Restore selection after reload.
            final long savedId = entry.getId();
            Platform.runLater(() -> {
                CredentialEntry toSelect = credentialList.getItems().stream()
                    .filter(c -> c.getId() == savedId).findFirst().orElse(null);
                if (toSelect != null) {
                    credentialList.getSelectionModel().select(toSelect);
                }
            });
            showInfo(I18nManager.t("credential_dialog.save_success", "name", entry.getName()));
        } catch (Exception e) {
            showError(I18nManager.t("connection_dialog.save_failed", "message", e.getMessage()));
        }
    }

    @FXML
    private void onDelete() {
        if (selectedEntry == null) {
            showError("No credential selected");  // TODO: i18n
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18nManager.t("credential_dialog.delete"));
        alert.setHeaderText(null);
        alert.setContentText(I18nManager.t("credential_dialog.delete_confirm", "name", selectedEntry.getName()));
        com.raindrop.util.ThemeManager.apply(alert);
        Stage alertStage = (Stage) alert.getDialogPane().getScene().getWindow();
        alertStage.setAlwaysOnTop(true);

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    credentialManager.delete(selectedEntry.getId());
                    loadCredentials();
                    clearFields();
                    showInfo("Credential deleted");  // TODO: i18n
                } catch (Exception e) {
                    showError(I18nManager.t("connection_dialog.save_failed", "message", e.getMessage()));
                }
            }
        });
    }

    @FXML
    private void onBrowseKey() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18nManager.t("connection_dialog.select_key_file"));
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Key Files", "*.pem", "*.key", "id_rsa", "id_ed25519", "*.*"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            keyPathField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    private void onClose() {
        Stage stage = (Stage) credentialList.getScene().getWindow();
        stage.close();
    }

    private void clearFields() {
        nameField.clear();
        usernameField.clear();
        passwordField.clear();
        keyPathField.clear();
        keyPassField.clear();
        typeComboBox.getSelectionModel().selectFirst();
    }

    private void showError(String message) {
        com.raindrop.util.DialogUtil.showMessage("Error", message);
    }

    private void showInfo(String message) {
        com.raindrop.util.DialogUtil.showMessage("Info", message);
    }
}
