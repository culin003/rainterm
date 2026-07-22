package com.raindrop.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 国际化资源管理器。
 *
 * <p>设计原则：
 * <ul>
 *   <li>单例模式，全局唯一
 *   <li>支持动态语言切换
 *   <li>支持占位符参数替换
 *   <li>语言配置持久化到 ConfigManager
 * </ul>
 */
public class I18nManager {

    public static final String KEY_LANGUAGE = "language";
    public static final String LANG_ZH_CN = "zh_CN";
    public static final String LANG_ZH_TW = "zh_TW";
    public static final String LANG_EN_US = "en_US";

    private static I18nManager instance;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, JsonNode> languageCache = new ConcurrentHashMap<>();

    private String currentLanguage;
    private JsonNode currentMessages;

    private I18nManager() {
        String savedLang = ConfigManager.getInstance().get(KEY_LANGUAGE);
        if (savedLang != null && isValidLanguage(savedLang)) {
            setLanguage(savedLang);
        } else {
            String systemLang = Locale.getDefault().toString();
            if (systemLang.startsWith("zh")) {
                if (systemLang.contains("TW") || systemLang.contains("HK")) {
                    setLanguage(LANG_ZH_TW);
                } else {
                    setLanguage(LANG_ZH_CN);
                }
            } else {
                setLanguage(LANG_EN_US);
            }
        }
    }

    public static synchronized I18nManager getInstance() {
        if (instance == null) {
            instance = new I18nManager();
        }
        return instance;
    }

    /**
     * 设置当前语言。
     * @param language 语言代码：zh_CN / zh_TW / en_US
     */
    public void setLanguage(String language) {
        if (!isValidLanguage(language)) {
            throw new IllegalArgumentException("Unknown language: " + language);
        }
        this.currentLanguage = language;
        this.currentMessages = loadLanguageFile(language);
        ConfigManager.getInstance().set(KEY_LANGUAGE, language);
    }

    /**
     * 获取当前语言。
     */
    public String getLanguage() {
        return currentLanguage;
    }

    /**
     * 获取国际化字符串。
     * @param key 键，如 "common.ok"
     */
    public String get(String key) {
        String[] parts = key.split("\\.");
        JsonNode node = currentMessages;
        for (String part : parts) {
            if (node == null) break;
            node = node.get(part);
        }
        return node != null && !node.isMissingNode() ? node.asText() : key;
    }

    /**
     * 获取国际化字符串，支持占位符替换。
     * 占位符格式：{key}
     * @param key 键
     * @param params 参数，偶数索引为键名，奇数索引为值
     */
    public String get(String key, String... params) {
        String text = get(key);
        if (params != null) {
            for (int i = 0; i < params.length - 1; i += 2) {
                String placeholder = "{" + params[i] + "}";
                String value = params[i + 1];
                text = text.replace(placeholder, value);
            }
        }
        return text;
    }

    /**
     * 静态快捷方法。
     */
    public static String t(String key) {
        return getInstance().get(key);
    }

    /**
     * 静态快捷方法，支持占位符。
     */
    public static String t(String key, String... params) {
        return getInstance().get(key, params);
    }

    private JsonNode loadLanguageFile(String language) {
        if (languageCache.containsKey(language)) {
            return languageCache.get(language);
        }
        String path = "/i18n/messages_" + language + ".json";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Language file not found: " + path);
            }
            JsonNode root = objectMapper.readTree(is);
            languageCache.put(language, root);
            return root;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load language file: " + path, e);
        }
    }

    private boolean isValidLanguage(String lang) {
        return LANG_ZH_CN.equals(lang)
            || LANG_ZH_TW.equals(lang)
            || LANG_EN_US.equals(lang);
    }

    /**
     * 获取所有支持的语言列表。
     * @return 有序的语言映射：[languageCode -> displayName]
     */
    public Map<String, String> getSupportedLanguages() {
        Map<String, String> langs = new LinkedHashMap<>();
        langs.put(LANG_ZH_CN, get("settings.language_zh_cn"));
        langs.put(LANG_ZH_TW, get("settings.language_zh_tw"));
        langs.put(LANG_EN_US, get("settings.language_en_us"));
        return langs;
    }
}
