package com.raindrop.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class I18nManagerTest {

    @BeforeEach
    void setUp() {
        // Reset to default language before each test
        I18nManager.getInstance().setLanguage(I18nManager.LANG_ZH_CN);
    }

    @Test
    void testGetInstance() {
        I18nManager instance1 = I18nManager.getInstance();
        I18nManager instance2 = I18nManager.getInstance();
        assertSame(instance1, instance2, "I18nManager should be a singleton");
    }

    @Test
    void testGetSimpleKey() {
        assertEquals("确定", I18nManager.t("common.ok"));
        assertEquals("取消", I18nManager.t("common.cancel"));
    }

    @Test
    void testGetWithParams() {
        String result = I18nManager.t("session_list.delete_confirm", "name", "test-server");
        assertEquals("确定要删除 'test-server' 吗？", result);
    }

    @Test
    void testSetLanguage() {
        I18nManager.getInstance().setLanguage(I18nManager.LANG_EN_US);
        assertEquals("OK", I18nManager.t("common.ok"));

        I18nManager.getInstance().setLanguage(I18nManager.LANG_ZH_TW);
        assertEquals("確定", I18nManager.t("common.ok"));
    }

    @Test
    void testGetLanguage() {
        assertEquals(I18nManager.LANG_ZH_CN, I18nManager.getInstance().getLanguage());
        I18nManager.getInstance().setLanguage(I18nManager.LANG_EN_US);
        assertEquals(I18nManager.LANG_EN_US, I18nManager.getInstance().getLanguage());
    }

    @Test
    void testGetSupportedLanguages() {
        var languages = I18nManager.getInstance().getSupportedLanguages();
        assertEquals(3, languages.size());
        assertTrue(languages.containsKey(I18nManager.LANG_ZH_CN));
        assertTrue(languages.containsKey(I18nManager.LANG_ZH_TW));
        assertTrue(languages.containsKey(I18nManager.LANG_EN_US));
    }

    @Test
    void testUnknownKeyReturnsKey() {
        String unknownKey = "this.key.does.not.exist";
        assertEquals(unknownKey, I18nManager.t(unknownKey));
    }

    @Test
    void testInvalidLanguageThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            I18nManager.getInstance().setLanguage("invalid_lang");
        });
    }

    /**
     * Verify the duplicate-connection feature's i18n keys resolve in all
     * three supported languages. {@code I18nManager} falls back to the key
     * string itself when a translation is missing, so the strongest signal
     * is that the returned value differs from the key.
     */
    @Test
    void testDuplicateConnectionKeysResolveInAllLanguages() {
        String[] keys = {
            "connection_dialog.title_duplicate",
            "connection_dialog.copy_suffix",
            "session_list.duplicate"
        };
        for (String code : new String[] {
                I18nManager.LANG_EN_US, I18nManager.LANG_ZH_CN, I18nManager.LANG_ZH_TW}) {
            I18nManager.getInstance().setLanguage(code);
            for (String k : keys) {
                String translated = I18nManager.t(k);
                assertNotNull(translated, "Missing translation for " + k + " in " + code);
                assertNotEquals(k, translated,
                    "I18nManager returned the key for " + k + " in " + code
                        + " — JSON file is missing this entry");
            }
        }
    }

    @Test
    void testFontSettingKeysResolveInAllLanguages() {
        String[] keys = {
            "settings.font_family",
            "settings.font_size",
            "settings.font_preview",
            "settings.font_preview_text",
            "settings.font_default",
            "settings.ui_font_family",
            "settings.ui_font_size"
        };
        for (String code : new String[] {
                I18nManager.LANG_EN_US, I18nManager.LANG_ZH_CN, I18nManager.LANG_ZH_TW}) {
            I18nManager.getInstance().setLanguage(code);
            for (String k : keys) {
                assertNotEquals(k, I18nManager.t(k),
                    "Missing translation for " + k + " in " + code);
            }
        }
    }
}
