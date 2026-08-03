package com.raindrop.util;

import com.raindrop.storage.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages application settings stored in the database.
 *
 * <p>The in-memory cache is a {@link ConcurrentHashMap} — this instance
 * is accessed from both the JavaFX Application Thread (SettingsView save,
 * MainController lookups) and virtual threads (TabManager reads encoding,
 * SftpService indirectly). {@code ConcurrentHashMap} disallows null values,
 * so a DB row whose {@code value} column is SQL NULL is stored as the
 * sentinel {@link #NULL_SENTINEL} and translated back to {@code null} on
 * read. Skipping the cache for null-valued rows would work too but would
 * cause a fresh DB round-trip on every access to a legitimately-null key.
 */
public class ConfigManager {
    private static ConfigManager instance;
    private static final String NULL_SENTINEL = "\u0000RAINDROP_NULL\u0000";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private ConfigManager() {}

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /**
     * Get a setting value.
     * @param key Setting key
     * @param defaultValue Default value if not found
     */
    public String get(String key, String defaultValue) {
        String cached = cache.get(key);
        if (cached != null) {
            return NULL_SENTINEL.equals(cached) ? null : cached;
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString("value");
                    cache.put(key, value == null ? NULL_SENTINEL : value);
                    return value;
                }
            }
        } catch (SQLException e) {
            // Ignore and return default
        }

        return defaultValue;
    }

    /**
     * Get a setting value (no default).
     */
    public String get(String key) {
        return get(key, null);
    }

    /**
     * Get an integer setting.
     */
    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return defaultValue;
    }

    /**
     * Get a boolean setting.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    /**
     * Set a setting value.
     */
    public void set(String key, String value) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO app_setting (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            cache.put(key, value == null ? NULL_SENTINEL : value);
        } catch (SQLException e) {
            // Log error
        }
    }

    /**
     * Remove a setting.
     */
    public void remove(String key) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM app_setting WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
            cache.remove(key);
        } catch (SQLException e) {
            // Log error
        }
    }

    /**
     * Get all settings.
     */
    public Map<String, String> getAll() {
        Map<String, String> settings = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT key, value FROM app_setting");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                settings.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            // Log error
        }
        return settings;
    }

    // Common setting keys
    public static final String KEY_DEFAULT_ENCODING = "default_encoding";
    public static final String KEY_TERMINAL_THEME = "terminal_theme";
    public static final String KEY_FONT_SIZE = "font_size";
    public static final String KEY_TERMINAL_FONT_FAMILY = "terminal_font_family";
    public static final String KEY_UI_FONT_FAMILY = "ui_font_family";
    public static final String KEY_UI_FONT_SIZE = "ui_font_size";
    public static final String KEY_WINDOW_WIDTH = "window_width";
    public static final String KEY_WINDOW_HEIGHT = "window_height";

    // Master password + auto-lock feature keys
    public static final String KEY_MASTER_PASSWORD_VERIFIER = "master_password_verifier";
    public static final String KEY_MASTER_PASSWORD_HINT = "master_password_hint";
    public static final String KEY_MASTER_PASSWORD_KDF_VERSION = "master_password_kdf_version";
    public static final String KEY_AUTO_LOCK_TIMEOUT_SECONDS = "auto_lock_timeout_seconds";
}
