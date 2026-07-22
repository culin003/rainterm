package com.raindrop.security;

import com.raindrop.storage.DatabaseManager;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.CryptoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityManagerTest {

    @BeforeEach
    public void setUp() throws Exception {
        // Ensure clean singleton + wipe app_setting + credentials + profiles.
        SecurityManager.resetForTests();
        try (Connection c = DatabaseManager.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM app_setting");
            st.executeUpdate("DELETE FROM credential");
            st.executeUpdate("DELETE FROM connection_profile");
        }
        // Purge cache
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER);
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_HINT);
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION);
    }

    @AfterEach
    public void tearDown() {
        SecurityManager.resetForTests();
        CryptoUtil.unlockWithPassword("test-master-password");
    }

    @Test
    public void testCompleteSetupFreshInstall_writesVerifierAndUnlocks() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        assertEquals(SecurityManager.State.UNINITIALIZED, sm.getState());
        sm.completeSetup("hunter2", "hint-text");
        assertEquals(SecurityManager.State.UNLOCKED, sm.getState());
        assertTrue(CryptoUtil.isUnlocked());
        assertNotNull(ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER));
        assertEquals("hint-text", ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_HINT));
        assertEquals(1, ConfigManager.getInstance().getInt(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION, 0));
    }

    @Test
    public void testUnlockCorrectPassword_transitionsToUnlocked() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        sm.completeSetup("hunter2", "");
        sm.lock();
        assertEquals(SecurityManager.State.LOCKED, sm.getState());
        assertTrue(sm.unlock("hunter2"));
        assertEquals(SecurityManager.State.UNLOCKED, sm.getState());
    }

    @Test
    public void testUnlockIncorrectPassword_staysLocked() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        sm.completeSetup("hunter2", "");
        sm.lock();
        assertFalse(sm.unlock("wrong"));
        assertEquals(SecurityManager.State.LOCKED, sm.getState());
        assertFalse(CryptoUtil.isUnlocked());
        assertThrows(IllegalStateException.class, () -> CryptoUtil.encrypt("x"));
    }

    @Test
    public void testCompleteSetupIsIdempotent_secondCallRejected() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        sm.completeSetup("hunter2", "");
        assertThrows(IllegalStateException.class, () -> sm.completeSetup("other", ""));
    }

    @Test
    public void testDestructiveReset_wipesAndReturnsToUninitialized() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        sm.completeSetup("hunter2", "hint");
        // Seed some rows.
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connection_profile (name, host) VALUES ('x', 'h')")) {
            ps.executeUpdate();
        }
        sm.destructiveReset();
        assertEquals(SecurityManager.State.UNINITIALIZED, sm.getState());
        assertFalse(CryptoUtil.isUnlocked());
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM connection_profile");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
        assertNull(ConfigManager.getInstance().get(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER));
    }

    @Test
    public void testLockClearsActiveEncryptor() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        sm.completeSetup("hunter2", "");
        assertTrue(CryptoUtil.isUnlocked());
        sm.lock();
        assertFalse(CryptoUtil.isUnlocked());
    }

    @Test
    public void testChangeMasterPassword_reencryptsData() throws Exception {
        SecurityManager sm = SecurityManager.getInstance();
        sm.bootstrap();
        sm.completeSetup("first", "");
        // Encrypt a payload with the current key and save it in profile.password.
        String cipher = CryptoUtil.encrypt("mydata");
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connection_profile (name, host, password) VALUES ('n','h',?)")) {
            ps.setString(1, cipher);
            ps.executeUpdate();
        }
        sm.changeMasterPassword("first", "second", "");
        // Old cipher should NOT decrypt with the new key; the row should have been re-encrypted.
        String updated;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password FROM connection_profile WHERE name='n'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            updated = rs.getString(1);
        }
        assertNotEquals(cipher, updated);
        assertEquals("mydata", CryptoUtil.decrypt(updated));
    }
}
