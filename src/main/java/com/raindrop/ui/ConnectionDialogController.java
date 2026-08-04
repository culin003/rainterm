package com.raindrop.ui;

import com.raindrop.credential.CredentialEntry;
import com.raindrop.credential.CredentialManager;
import com.raindrop.storage.ConnectionProfile;
import com.raindrop.storage.ProfileRepository;
import com.raindrop.util.CryptoUtil;
import com.raindrop.util.I18nManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

/**
 * Dialog for creating / editing a {@link ConnectionProfile}.
 * Supports three auth types: reference-a-credential, inline-password, inline-key.
 * Non-relevant fields are hidden as the user changes the auth-type dropdown.
 */
public class ConnectionDialogController {

    private static final String AUTH_CREDENTIAL = "credential";
    private static final String AUTH_PASSWORD_INLINE = "password_inline";
    private static final String AUTH_KEY_INLINE = "key_inline";

    @FXML private TextField nameField;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private ChoiceBox<String> authTypeChoice;

    @FXML private Label credentialLabel;
    @FXML private ChoiceBox<CredentialItem> credentialChoice;

    @FXML private Label usernameLabel;
    @FXML private TextField usernameField;

    @FXML private Label passwordLabel;
    @FXML private PasswordField passwordField;

    @FXML private Label keyPathLabel;
    @FXML private TextField keyPathField;
    @FXML private Button keyBrowseButton;
    @FXML private Label keyPassLabel;
    @FXML private PasswordField keyPassField;

    @FXML private Label groupLabel;
    @FXML private TextField groupField;
    @FXML private Label encodingLabel;
    @FXML private ComboBox<String> encodingCombo;

    @FXML private Label nameLabel;
    @FXML private Label hostLabel;
    @FXML private Label portLabel;
    @FXML private Label authTypeLabel;
    @FXML private Button testButton;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    private MainController mainController;
    private ConnectionProfile existingProfile;
    private boolean duplicating;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Set all labels with i18n
        nameLabel.setText(I18nManager.t("connection_dialog.name"));
        hostLabel.setText(I18nManager.t("connection_dialog.host"));
        portLabel.setText(I18nManager.t("connection_dialog.port"));
        authTypeLabel.setText(I18nManager.t("connection_dialog.auth_type"));
        credentialLabel.setText(I18nManager.t("connection_dialog.select_credential"));
        usernameLabel.setText(I18nManager.t("connection_dialog.username"));
        passwordLabel.setText(I18nManager.t("connection_dialog.password"));
        keyPathLabel.setText(I18nManager.t("connection_dialog.key_path"));
        keyBrowseButton.setText(I18nManager.t("common.browse"));
        keyPassLabel.setText(I18nManager.t("connection_dialog.key_passphrase"));
        groupLabel.setText(I18nManager.t("connection_dialog.group"));
        encodingLabel.setText(I18nManager.t("connection_dialog.encoding"));
        testButton.setText(I18nManager.t("connection_dialog.test_connection"));
        saveButton.setText(I18nManager.t("common.save"));
        cancelButton.setText(I18nManager.t("common.cancel"));

        authTypeChoice.setItems(FXCollections.observableArrayList(
            AUTH_CREDENTIAL, AUTH_PASSWORD_INLINE, AUTH_KEY_INLINE));
        authTypeChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String type) {
                if (AUTH_CREDENTIAL.equals(type)) return I18nManager.t("connection_dialog.auth_credential");
                if (AUTH_PASSWORD_INLINE.equals(type)) return I18nManager.t("connection_dialog.auth_password_inline");
                if (AUTH_KEY_INLINE.equals(type)) return I18nManager.t("connection_dialog.auth_key_inline");
                return type;
            }
            @Override public String fromString(String s) { return s; }
        });
        authTypeChoice.getSelectionModel().selectedItemProperty()
            .addListener((obs, oldV, newV) -> applyVisibility(newV));
        authTypeChoice.getSelectionModel().select(AUTH_PASSWORD_INLINE);

        encodingCombo.setItems(FXCollections.observableArrayList(
            "UTF-8", "GBK", "GB18030", "Big5", "ISO-8859-1", "Shift_JIS", "EUC-KR"));
        encodingCombo.setValue("UTF-8");

        credentialChoice.setItems(FXCollections.observableArrayList());
        credentialChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(CredentialItem item) {
                return item == null ? "" : item.label;
            }
            @Override public CredentialItem fromString(String s) { return null; }
        });

        loadCredentialsAsync();
    }

    private void loadCredentialsAsync() {
        com.raindrop.core.TaskExecutor.submit(() -> {
            try {
                List<CredentialEntry> entries = new CredentialManager().findAll();
                Platform.runLater(() -> {
                    credentialChoice.getItems().clear();
                    credentialChoice.getItems().add(new CredentialItem(null, I18nManager.t("connection_dialog.no_credential")));
                    for (CredentialEntry e : entries) {
                        credentialChoice.getItems().add(new CredentialItem(e.getId(),
                            e.getName() + " — " + e.getUsername() + " [" + e.getType() + "]"));
                    }
                    // If we were editing a profile that pointed at a credential, re-select it.
                    if (existingProfile != null && existingProfile.getCredentialId() != null) {
                        selectCredentialById(existingProfile.getCredentialId());
                    } else {
                        credentialChoice.getSelectionModel().selectFirst();
                    }
                });
            } catch (SQLException ex) {
                Platform.runLater(() -> alert(Alert.AlertType.ERROR,
                    I18nManager.t("errors.load_dialog_failed", "message", ex.getMessage())));
            }
        });
    }

    private void selectCredentialById(Long id) {
        for (CredentialItem item : credentialChoice.getItems()) {
            if (id.equals(item.id)) {
                credentialChoice.getSelectionModel().select(item);
                return;
            }
        }
    }

    public void setProfile(ConnectionProfile profile) {
        this.existingProfile = profile;
        if (profile == null) return;
        nameField.setText(profile.getName());
        hostField.setText(profile.getHost());
        portField.setText(String.valueOf(profile.getPort()));
        usernameField.setText(profile.getUsername());
        groupField.setText(profile.getGroupName());
        if (profile.getEncoding() != null && !profile.getEncoding().isBlank()) {
            encodingCombo.setValue(profile.getEncoding());
        }
        String type = profile.getAuthType();
        if (type == null || type.isEmpty()) type = AUTH_PASSWORD_INLINE;
        authTypeChoice.getSelectionModel().select(type);
        keyPathField.setText(profile.getKeyPath() != null ? profile.getKeyPath() : "");
        // We do not repopulate encrypted password / key passphrase — user must re-enter if changing.
        applyVisibility(type);
    }

    public void setDuplicateSource(ConnectionProfile source) {
        this.duplicating = true;
        setProfile(source);
        // Encrypted password / key passphrase are carried over automatically in
        // createProfile() when the fields are left blank. Only tweak the name so
        // the user can tell the copy apart from the original.
        nameField.setText(source.getName() + " - " + I18nManager.t("connection_dialog.copy_suffix"));
    }

    private void applyVisibility(String type) {
        boolean isCredential = AUTH_CREDENTIAL.equals(type);
        boolean isPasswordInline = AUTH_PASSWORD_INLINE.equals(type);
        boolean isKeyInline = AUTH_KEY_INLINE.equals(type);

        setVisible(credentialLabel, isCredential);
        setVisible(credentialChoice, isCredential);

        setVisible(usernameLabel, !isCredential);
        setVisible(usernameField, !isCredential);

        setVisible(passwordLabel, isPasswordInline);
        setVisible(passwordField, isPasswordInline);

        setVisible(keyPathLabel, isKeyInline);
        setVisible(keyPathField, isKeyInline);
        setVisible(keyBrowseButton, isKeyInline);
        setVisible(keyPassLabel, isKeyInline);
        setVisible(keyPassField, isKeyInline);
    }

    private static void setVisible(javafx.scene.Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @FXML
    private void onBrowseKey() {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18nManager.t("connection_dialog.select_key_file"));
        String home = System.getProperty("user.home");
        File sshDir = new File(home, ".ssh");
        fc.setInitialDirectory(sshDir.isDirectory() ? sshDir : new File(home));
        File selected = fc.showOpenDialog(null);
        if (selected != null) keyPathField.setText(selected.getAbsolutePath());
    }

    @FXML
    private void onTestConnection() {
        ConnectionProfile profile = createProfile();
        if (profile == null) return;

        com.raindrop.core.TaskExecutor.submit(() -> {
            com.raindrop.core.SshSession session = new com.raindrop.core.SshSession(profile);
            try {
                session.connect();
                Platform.runLater(() -> alert(Alert.AlertType.INFORMATION, I18nManager.t("connection_dialog.test_success")));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Platform.runLater(() -> alert(Alert.AlertType.ERROR, I18nManager.t("connection_dialog.test_failed", "message", msg)));
            } finally {
                try { session.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    @FXML
    private void onSave() {
        ConnectionProfile profile = createProfile();
        if (profile == null) return;
        try {
            ProfileRepository repo = new ProfileRepository();
            if (existingProfile != null && !duplicating) {
                profile.setId(existingProfile.getId());
                repo.update(profile);
            } else {
                repo.save(profile);
            }
            closeDialog();
            mainController.updateStatus(I18nManager.t("connection_dialog.save_success", "name", profile.getName()));
            mainController.refreshSessionList();
        } catch (Exception e) {
            alert(Alert.AlertType.ERROR, I18nManager.t("connection_dialog.save_failed", "message", e.getMessage()));
        }
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    private ConnectionProfile createProfile() {
        String name = nameField.getText();
        String host = hostField.getText();
        if (name == null || name.isEmpty() || host == null || host.isEmpty()) {
            alert(Alert.AlertType.WARNING, I18nManager.t("connection_dialog.error_name_host_required"));
            return null;
        }

        int port;
        try { port = Integer.parseInt(portField.getText()); }
        catch (NumberFormatException e) { port = 22; }

        String type = authTypeChoice.getSelectionModel().getSelectedItem();
        if (type == null) type = AUTH_PASSWORD_INLINE;

        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setHost(host);
        profile.setPort(port);
        profile.setGroupName(groupField.getText());
        String encoding = encodingCombo.getValue();
        profile.setEncoding((encoding == null || encoding.isBlank()) ? "UTF-8" : encoding.trim());
        profile.setAuthType(type);

        if (AUTH_CREDENTIAL.equals(type)) {
            CredentialItem sel = credentialChoice.getSelectionModel().getSelectedItem();
            if (sel == null || sel.id == null) {
                alert(Alert.AlertType.WARNING, I18nManager.t("connection_dialog.error_credential_required"));
                return null;
            }
            profile.setCredentialId(sel.id);
            // Cache the credential's username in the profile for display in SessionListPane.
            // SshSession will re-fetch from DB at connect time.
            try {
                CredentialEntry entry = new CredentialManager().findById(sel.id);
                if (entry != null && entry.getUsername() != null) {
                    profile.setUsername(entry.getUsername());
                }
            } catch (SQLException ignored) {
            }
        } else {
            String username = usernameField.getText();
            if (username == null || username.isEmpty()) {
                alert(Alert.AlertType.WARNING, I18nManager.t("connection_dialog.error_username_required"));
                return null;
            }
            profile.setUsername(username);

            if (AUTH_PASSWORD_INLINE.equals(type)) {
                String pw = passwordField.getText();
                if (pw != null && !pw.isEmpty()) {
                    profile.setPassword(CryptoUtil.encrypt(pw));
                } else if (existingProfile != null && existingProfile.getPassword() != null) {
                    // Keep prior password unchanged when the user did not re-enter it.
                    profile.setPassword(existingProfile.getPassword());
                }
            } else if (AUTH_KEY_INLINE.equals(type)) {
                String path = keyPathField.getText();
                if (path == null || path.isEmpty()) {
                    alert(Alert.AlertType.WARNING, I18nManager.t("connection_dialog.error_key_path_required"));
                    return null;
                }
                profile.setKeyPath(path);
                String keyPass = keyPassField.getText();
                if (keyPass != null && !keyPass.isEmpty()) {
                    profile.setKeyPass(CryptoUtil.encrypt(keyPass));
                } else if (existingProfile != null && existingProfile.getKeyPass() != null) {
                    profile.setKeyPass(existingProfile.getKeyPass());
                }
            }
        }
        return profile;
    }

    private void alert(Alert.AlertType type, String message) {
        String title = type == Alert.AlertType.ERROR ? "Error"
            : type == Alert.AlertType.WARNING ? "Validation" : "Info";
        com.raindrop.util.DialogUtil.showMessage(title, message);
    }

    private void closeDialog() {
        Stage stage = (Stage) nameField.getScene().getWindow();
        stage.close();
    }

    /** Wrapper for credential drop-down items. */
    private record CredentialItem(Long id, String label) {}
}
