package com.raindrop.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class I18nManagerLanguageTest {

    @Test
    void testDefaultLanguageIsChinese() {
        // 验证默认语言是中文
        I18nManager manager = I18nManager.getInstance();
        // 先重置为中文以便测试
        manager.setLanguage(I18nManager.LANG_ZH_CN);

        assertEquals("zh_CN", manager.getLanguage());
        assertEquals("复制", I18nManager.t("terminal.copy"));
        assertEquals("粘贴", I18nManager.t("terminal.paste"));
        assertEquals("全选", I18nManager.t("terminal.select_all"));
        assertEquals("清空缓冲区", I18nManager.t("terminal.clear_buffer"));
        assertEquals("向上翻页", I18nManager.t("terminal.page_up"));
    }

    @Test
    void testLanguageSwitching() {
        I18nManager manager = I18nManager.getInstance();

        // 测试英文
        manager.setLanguage(I18nManager.LANG_EN_US);
        assertEquals("Copy", I18nManager.t("terminal.copy"));
        assertEquals("Paste", I18nManager.t("terminal.paste"));

        // 测试繁体中文
        manager.setLanguage(I18nManager.LANG_ZH_TW);
        assertEquals("複製", I18nManager.t("terminal.copy"));
        assertEquals("貼上", I18nManager.t("terminal.paste"));

        // 恢复为简体中文
        manager.setLanguage(I18nManager.LANG_ZH_CN);
    }
}
