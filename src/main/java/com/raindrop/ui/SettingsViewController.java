package com.raindrop.ui;

import com.raindrop.util.ConfigManager;
import com.raindrop.util.FontManager;
import com.raindrop.util.I18nManager;
import com.raindrop.util.ThemeManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SettingsViewController {
    @FXML private Label themeLabel;
    @FXML private Label fontFamilyLabel;
    @FXML private Label fontSizeLabel;
    @FXML private Label fontPreviewLabel;
    @FXML private Label fontPreview;
    @FXML private Label uiFontFamilyLabel;
    @FXML private Label uiFontSizeLabel;
    @FXML private Label encodingLabel;
    @FXML private Label languageLabel;
    @FXML private Label idleTimeoutLabel;
    @FXML private Label idleTimeoutHint;
    @FXML private Label selectToCopyLabel;
    @FXML private Label masterPasswordLabel;
    @FXML private Button changeMasterPasswordButton;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    @FXML private ComboBox<String> themeCombo;
    @FXML private ComboBox<String> fontFamilyCombo;
    @FXML private ComboBox<String> uiFontFamilyCombo;
    @FXML private ComboBox<String> encodingCombo;
    @FXML private ComboBox<String> languageCombo;
    @FXML private Spinner<Integer> fontSizeSpinner;
    @FXML private Spinner<Integer> uiFontSizeSpinner;
    @FXML private Spinner<Integer> idleTimeoutSpinner;
    @FXML private CheckBox selectToCopyCheckBox;

    private final ConfigManager config = ConfigManager.getInstance();
    private MainController mainController;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        initLabels();

        themeCombo.setItems(FXCollections.observableArrayList("dark", "light", "solarized-dark", "green-on-black"));
        themeCombo.setValue(config.get(ConfigManager.KEY_TERMINAL_THEME, "dark"));

        encodingCombo.setItems(FXCollections.observableArrayList("UTF-8", "GBK", "GB18030", "ISO-8859-1"));
        encodingCombo.setValue(config.get(ConfigManager.KEY_DEFAULT_ENCODING, "UTF-8"));

        initLanguageCombo();

        fontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            TabManager.MIN_FONT_SIZE, TabManager.MAX_FONT_SIZE,
            config.getInt(ConfigManager.KEY_FONT_SIZE, TabManager.DEFAULT_FONT_SIZE)));

        initFontCombos();

        idleTimeoutSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            0, 86400, config.getInt(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, 600), 30));

        selectToCopyCheckBox.setSelected(config.getBoolean(ConfigManager.KEY_SELECT_TO_COPY, true));
    }

    private void initLabels() {
        themeLabel.setText(I18nManager.t("settings.theme"));
        fontFamilyLabel.setText(I18nManager.t("settings.font_family"));
        fontSizeLabel.setText(I18nManager.t("settings.font_size"));
        fontPreviewLabel.setText(I18nManager.t("settings.font_preview"));
        uiFontFamilyLabel.setText(I18nManager.t("settings.ui_font_family"));
        uiFontSizeLabel.setText(I18nManager.t("settings.ui_font_size"));
        encodingLabel.setText(I18nManager.t("settings.default_encoding"));
        languageLabel.setText(I18nManager.t("settings.language"));
        idleTimeoutLabel.setText(I18nManager.t("settings.auto_lock_timeout"));
        idleTimeoutHint.setText(I18nManager.t("settings.auto_lock_never"));
        selectToCopyLabel.setText(I18nManager.t("settings.select_to_copy"));
        masterPasswordLabel.setText(I18nManager.t("settings.master_password"));
        changeMasterPasswordButton.setText(I18nManager.t("common.change"));
        saveButton.setText(I18nManager.t("common.save"));
        cancelButton.setText(I18nManager.t("common.cancel"));
    }

    private void initLanguageCombo() {
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
    }

    private void initFontCombos() {
        List<String> monoFamilies = FontManager.monospaceFamilies();
        fontFamilyCombo.setItems(FXCollections.observableArrayList(monoFamilies));
        fontFamilyCombo.setValue(FontManager.resolveTerminalFamily(
            config.get(ConfigManager.KEY_TERMINAL_FONT_FAMILY, FontManager.MONO_CJK_FAMILY)));

        // Blank = "inherit from the theme stylesheet"; shown as a localized placeholder.
        List<String> uiFamilies = new ArrayList<>();
        uiFamilies.add("");
        uiFamilies.addAll(Font.getFamilies());
        uiFontFamilyCombo.setItems(FXCollections.observableArrayList(uiFamilies));
        uiFontFamilyCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String family) {
                return (family == null || family.isBlank()) ? I18nManager.t("settings.font_default") : family;
            }
            @Override public String fromString(String s) { return s; }
        });
        String uiFamily = config.get(ConfigManager.KEY_UI_FONT_FAMILY, "");
        uiFontFamilyCombo.setValue(uiFamilies.contains(uiFamily) ? uiFamily : "");

        uiFontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            ThemeManager.MIN_UI_FONT_SIZE, ThemeManager.MAX_UI_FONT_SIZE,
            config.getInt(ConfigManager.KEY_UI_FONT_SIZE, ThemeManager.DEFAULT_UI_FONT_SIZE)));

        fontPreview.setText(I18nManager.t("settings.font_preview_text"));
        updateFontPreview();
        fontFamilyCombo.valueProperty().addListener((obs, o, n) -> updateFontPreview());
        fontSizeSpinner.valueProperty().addListener((obs, o, n) -> updateFontPreview());
    }

    private void updateFontPreview() {
        String family = fontFamilyCombo.getValue();
        Integer size = fontSizeSpinner.getValue();
        if (family == null || size == null) return;
        fontPreview.setFont(Font.font(family, size));
    }

    @FXML
    private void onSave() {
        config.set(ConfigManager.KEY_TERMINAL_THEME, themeCombo.getValue());
        config.set(ConfigManager.KEY_DEFAULT_ENCODING, encodingCombo.getValue());
        config.set(ConfigManager.KEY_FONT_SIZE, String.valueOf(fontSizeSpinner.getValue()));
        config.set(ConfigManager.KEY_TERMINAL_FONT_FAMILY, fontFamilyCombo.getValue());
        config.set(ConfigManager.KEY_UI_FONT_FAMILY, uiFontFamilyCombo.getValue());
        config.set(ConfigManager.KEY_UI_FONT_SIZE, String.valueOf(uiFontSizeSpinner.getValue()));
        config.set(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, String.valueOf(idleTimeoutSpinner.getValue()));
        config.set(ConfigManager.KEY_SELECT_TO_COPY, String.valueOf(selectToCopyCheckBox.isSelected()));

        // Save language setting
        String newLang = languageCombo.getValue();
        if (newLang != null && !newLang.equals(I18nManager.getInstance().getLanguage())) {
            I18nManager.getInstance().setLanguage(newLang);
        }

        if (mainController != null) {
            mainController.applyTheme(themeCombo.getValue());
            mainController.applyFontSettings(fontFamilyCombo.getValue(), fontSizeSpinner.getValue());
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
