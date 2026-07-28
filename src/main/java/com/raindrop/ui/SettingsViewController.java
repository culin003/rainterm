package com.raindrop.ui;

import com.raindrop.util.ConfigManager;
import com.raindrop.util.I18nManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.stage.Stage;

import java.util.Map;

public class SettingsViewController {
    @FXML private Label themeLabel;
    @FXML private Label fontSizeLabel;
    @FXML private Label encodingLabel;
    @FXML private Label languageLabel;
    @FXML private Label idleTimeoutLabel;
    @FXML private Label idleTimeoutHint;
    @FXML private Label masterPasswordLabel;
    @FXML private Button changeMasterPasswordButton;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    @FXML private ComboBox<String> themeCombo;
    @FXML private ComboBox<String> encodingCombo;
    @FXML private ComboBox<String> languageCombo;
    @FXML private Spinner<Integer> fontSizeSpinner;
    @FXML private Spinner<Integer> idleTimeoutSpinner;

    private final ConfigManager config = ConfigManager.getInstance();
    private MainController mainController;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Initialize labels with i18n
        themeLabel.setText(I18nManager.t("settings.theme"));
        fontSizeLabel.setText(I18nManager.t("settings.font_size"));
        encodingLabel.setText(I18nManager.t("settings.default_encoding"));
        languageLabel.setText(I18nManager.t("settings.language"));
        idleTimeoutLabel.setText(I18nManager.t("settings.auto_lock_timeout"));
        idleTimeoutHint.setText(I18nManager.t("settings.auto_lock_never"));
        masterPasswordLabel.setText(I18nManager.t("settings.master_password"));
        changeMasterPasswordButton.setText(I18nManager.t("common.change"));
        saveButton.setText(I18nManager.t("common.save"));
        cancelButton.setText(I18nManager.t("common.cancel"));

        themeCombo.setItems(FXCollections.observableArrayList("dark", "light", "solarized-dark", "green-on-black"));
        themeCombo.setValue(config.get(ConfigManager.KEY_TERMINAL_THEME, "dark"));

        encodingCombo.setItems(FXCollections.observableArrayList("UTF-8", "GBK", "GB18030", "ISO-8859-1"));
        encodingCombo.setValue(config.get(ConfigManager.KEY_DEFAULT_ENCODING, "UTF-8"));

        // Initialize language combo
        Map<String, String> languages = I18nManager.getInstance().getSupportedLanguages();
        languageCombo.setItems(FXCollections.observableArrayList(languages.keySet().stream().toList()));
        languageCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                return languages.getOrDefault(code, code);
            }
            @Override public String fromString(String s) { return s; }
        });
        String currentLang = config.get(I18nManager.KEY_LANGUAGE, I18nManager.LANG_EN_US);
        languageCombo.setValue(languages.containsKey(currentLang) ? currentLang : I18nManager.LANG_EN_US);

        fontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            8, 32, config.getInt(ConfigManager.KEY_FONT_SIZE, 14)));

        idleTimeoutSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            0, 86400, config.getInt(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, 600), 30));
    }

    @FXML
    private void onSave() {
        config.set(ConfigManager.KEY_TERMINAL_THEME, themeCombo.getValue());
        config.set(ConfigManager.KEY_DEFAULT_ENCODING, encodingCombo.getValue());
        config.set(ConfigManager.KEY_FONT_SIZE, String.valueOf(fontSizeSpinner.getValue()));
        config.set(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, String.valueOf(idleTimeoutSpinner.getValue()));

        // Save language setting
        String newLang = languageCombo.getValue();
        if (newLang != null && !newLang.equals(I18nManager.getInstance().getLanguage())) {
            I18nManager.getInstance().setLanguage(newLang);
        }

        if (mainController != null) {
            mainController.applyTheme(themeCombo.getValue());
            mainController.updateStatus("Settings saved");  // TODO: i18n - requires restart for new language
        }
        closeDialog();
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    @FXML
    private void onChangeMasterPassword() {
        if (mainController != null) {
            mainController.openChangeMasterPasswordDialog();
        }
    }

    private void closeDialog() {
        Stage stage = (Stage) themeCombo.getScene().getWindow();
        stage.close();
    }
}
