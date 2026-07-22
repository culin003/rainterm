package com.raindrop.security;

import com.raindrop.core.TaskExecutor;
import com.raindrop.storage.DatabaseManager;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.CryptoUtil;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the master-password / lock lifecycle. Single source of truth for whether
 * the app is unlocked, locked, or waiting for first-time setup. FX-thread-only
 * for state transitions; non-FX threads can safely poll {@link #isLocked()}.
 */
public final class SecurityManager {
    /** Constant value encrypted at setup and decrypted on unlock to verify the password. */
    public static final String VERIFIER_TOKEN = "RAINDROP_VERIFIER_V1";

    public enum State { UNINITIALIZED, LOCKED, UNLOCKED }

    private static volatile SecurityManager instance;

    private final ReadOnlyBooleanWrapper lockedProperty = new ReadOnlyBooleanWrapper(true);
    private final AtomicBoolean lockedMirror = new AtomicBoolean(true);
    private volatile State state = State.UNINITIALIZED;

    private SecurityManager() {}

    public static SecurityManager getInstance() {
        SecurityManager local = instance;
        if (local != null) return local;
        synchronized (SecurityManager.class) {
            if (instance == null) instance = new SecurityManager();
            return instance;
        }
    }

    /** Test hook: reset the singleton so tests start clean. */
    public static void resetForTests() {
        synchronized (SecurityManager.class) {
            instance = null;
        }
        CryptoUtil.clearActiveEncryptor();
    }

    /** Determine initial state from persisted config. Called at app boot. */
    public void bootstrap() {
        String verifier = ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER);
        state = (verifier == null || verifier.isEmpty()) ? State.UNINITIALIZED : State.LOCKED;
        setLocked(true);
    }

    public State getState() { return state; }
    public boolean isLocked() { return lockedMirror.get(); }
    public boolean isUninitialized() { return state == State.UNINITIALIZED; }
    public ReadOnlyBooleanProperty lockedProperty() { return lockedProperty.getReadOnlyProperty(); }

    public String getHint() {
        return ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_HINT);
    }

    /**
     * First-time setup: derive key, run migration, write verifier + hint + version.
     * Runs synchronously; caller should invoke on a virtual thread and gate UI with
     * a progress indicator.
     */
    public synchronized void completeSetup(String masterPassword, String hint) throws Exception {
        if (state != State.UNINITIALIZED) {
            throw new IllegalStateException("Master password already set");
        }
        String derived = PasswordKdf.deriveJasyptPassword(masterPassword, PasswordKdf.CURRENT_VERSION);
        StandardPBEStringEncryptor newEnc = CryptoUtil.buildStrongEncryptor(derived);
        try (Connection conn = DatabaseManager.getConnection()) {
            MigrationRunner.run(conn, newEnc);
            writeMetadata(conn, newEnc, hint);
        }
        CryptoUtil.setActiveEncryptor(newEnc);
        state = State.UNLOCKED;
        setLocked(false);
    }

    private void writeMetadata(Connection conn, StandardPBEStringEncryptor enc, String hint) throws SQLException {
        String verifier = enc.encrypt(VERIFIER_TOKEN);
        upsert(conn, ConfigManager.KEY_MASTER_PASSWORD_VERIFIER, verifier);
        upsert(conn, ConfigManager.KEY_MASTER_PASSWORD_HINT, hint == null ? "" : hint);
        upsert(conn, ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION, String.valueOf(PasswordKdf.CURRENT_VERSION));
        // Rebuild ConfigManager cache so freshly-written keys are visible.
        ConfigManager.getInstance().remove("__nonexistent__");
        // Directly poke the settings so subsequent reads see the value.
        ConfigManager.getInstance().set(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER, verifier);
        ConfigManager.getInstance().set(ConfigManager.KEY_MASTER_PASSWORD_HINT, hint == null ? "" : hint);
        ConfigManager.getInstance().set(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION, String.valueOf(PasswordKdf.CURRENT_VERSION));
    }

    private void upsert(Connection conn, String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO app_setting (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /**
     * Attempt to unlock with the given master password. Returns true on success.
     * Runs synchronously; caller should invoke off the FX thread.
     */
    public synchronized boolean unlock(String masterPassword) {
        if (state != State.LOCKED) {
            throw new IllegalStateException("Cannot unlock from state " + state);
        }
        int version = ConfigManager.getInstance().getInt(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION, 1);
        String derived = PasswordKdf.deriveJasyptPassword(masterPassword, version);
        StandardPBEStringEncryptor enc = CryptoUtil.buildStrongEncryptor(derived);
        String verifier = ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER);
        if (verifier == null || verifier.isEmpty()) return false;
        try {
            String decoded = enc.decrypt(verifier);
            if (!VERIFIER_TOKEN.equals(decoded)) return false;
        } catch (EncryptionOperationNotPossibleException e) {
            return false;
        }
        CryptoUtil.setActiveEncryptor(enc);
        state = State.UNLOCKED;
        setLocked(false);
        return true;
    }

    /** Change master password (requires already unlocked). Re-encrypts all data in one tx. */
    public synchronized void changeMasterPassword(String currentPassword, String newPassword, String newHint) throws Exception {
        if (state != State.UNLOCKED) throw new IllegalStateException("Must be unlocked to change password");
        // Verify current password against verifier.
        int version = ConfigManager.getInstance().getInt(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION, 1);
        String curDerived = PasswordKdf.deriveJasyptPassword(currentPassword, version);
        StandardPBEStringEncryptor curEnc = CryptoUtil.buildStrongEncryptor(curDerived);
        String verifier = ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER);
        try {
            if (!VERIFIER_TOKEN.equals(curEnc.decrypt(verifier))) throw new IllegalArgumentException("Current password incorrect");
        } catch (EncryptionOperationNotPossibleException e) {
            throw new IllegalArgumentException("Current password incorrect");
        }
        String newDerived = PasswordKdf.deriveJasyptPassword(newPassword, PasswordKdf.CURRENT_VERSION);
        StandardPBEStringEncryptor newEnc = CryptoUtil.buildStrongEncryptor(newDerived);
        try (Connection conn = DatabaseManager.getConnection()) {
            reencryptAll(conn, curEnc, newEnc);
            writeMetadata(conn, newEnc, newHint);
        }
        CryptoUtil.setActiveEncryptor(newEnc);
    }

    private void reencryptAll(Connection conn, StandardPBEStringEncryptor from, StandardPBEStringEncryptor to) throws SQLException {
        boolean prev = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            reencryptOne(conn, "connection_profile", "password", from, to);
            reencryptOne(conn, "connection_profile", "key_pass", from, to);
            reencryptOne(conn, "credential", "password", from, to);
            reencryptOne(conn, "credential", "key_data", from, to);
            reencryptOne(conn, "credential", "key_pass", from, to);
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e;
        } finally {
            conn.setAutoCommit(prev);
        }
    }

    private void reencryptOne(Connection conn, String table, String column, StandardPBEStringEncryptor from, StandardPBEStringEncryptor to) throws SQLException {
        String sel = "SELECT id, " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL AND " + column + " != ''";
        try (PreparedStatement s = conn.prepareStatement(sel);
             java.sql.ResultSet rs = s.executeQuery();
             PreparedStatement u = conn.prepareStatement("UPDATE " + table + " SET " + column + "=? WHERE id=?")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String plain = from.decrypt(rs.getString(2));
                u.setString(1, to.encrypt(plain));
                u.setLong(2, id);
                u.addBatch();
            }
            u.executeBatch();
        }
    }

    /** Lock the application. Idempotent. Safe to call from any thread. */
    public synchronized void lock() {
        if (state != State.UNLOCKED) return;
        CryptoUtil.clearActiveEncryptor();
        state = State.LOCKED;
        setLocked(true);
    }

    /** Destructive reset: wipe secrets, credentials, profiles, verifier. Return to UNINITIALIZED. */
    public synchronized void destructiveReset() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM credential");
                st.executeUpdate("DELETE FROM connection_profile");
                st.executeUpdate("DELETE FROM app_setting WHERE key IN ("
                    + "'" + ConfigManager.KEY_MASTER_PASSWORD_VERIFIER + "',"
                    + "'" + ConfigManager.KEY_MASTER_PASSWORD_HINT + "',"
                    + "'" + ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION + "')");
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
        }
        // Wipe cache of removed keys.
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER);
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_HINT);
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION);
        CryptoUtil.clearActiveEncryptor();
        state = State.UNINITIALIZED;
        setLocked(true);
    }

    private void setLocked(boolean locked) {
        lockedMirror.set(locked);
        if (Platform.isFxApplicationThread()) {
            lockedProperty.set(locked);
            return;
        }
        try {
            TaskExecutor.runOnFx(() -> lockedProperty.set(locked));
        } catch (IllegalStateException e) {
            // FX toolkit not initialized (headless tests). Skip the observable
            // notification; the AtomicBoolean mirror is authoritative anyway.
        }
    }
}
