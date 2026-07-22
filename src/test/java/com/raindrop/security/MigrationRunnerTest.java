package com.raindrop.security;

import com.raindrop.storage.DatabaseManager;
import com.raindrop.util.CryptoUtil;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class MigrationRunnerTest {

    @BeforeEach
    public void setUp() throws Exception {
        try (Connection c = DatabaseManager.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM connection_profile");
            st.executeUpdate("DELETE FROM credential");
            st.executeUpdate("DELETE FROM app_setting");
        }
    }

    @Test
    public void testMigrateFreshInstall_noRowsNoError() throws Exception {
        StandardPBEStringEncryptor newEnc = CryptoUtil.buildStrongEncryptor("new-key");
        try (Connection c = DatabaseManager.getConnection()) {
            assertDoesNotThrow(() -> MigrationRunner.run(c, newEnc));
        }
    }

    @Test
    public void testMigrateWithStrongLegacyCiphertext_reencryptsAllRows() throws Exception {
        StandardPBEStringEncryptor strongLegacy = CryptoUtil.buildLegacyStrongEncryptor();
        String cipher1 = strongLegacy.encrypt("secret-password");
        String cipher2 = strongLegacy.encrypt("secret-key-pass");
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connection_profile (name, host, password, key_pass) VALUES ('n','h',?,?)")) {
            ps.setString(1, cipher1);
            ps.setString(2, cipher2);
            ps.executeUpdate();
        }
        StandardPBEStringEncryptor newEnc = CryptoUtil.buildStrongEncryptor("new-key");
        try (Connection c = DatabaseManager.getConnection()) {
            MigrationRunner.run(c, newEnc);
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password, key_pass FROM connection_profile WHERE name='n'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("secret-password", newEnc.decrypt(rs.getString(1)));
            assertEquals("secret-key-pass", newEnc.decrypt(rs.getString(2)));
        }
    }

    @Test
    public void testMigrateWithLegacyDesCiphertext_reencryptsRows() throws Exception {
        StandardPBEStringEncryptor des = CryptoUtil.buildLegacyDesEncryptor();
        String cipher = des.encrypt("old-value");
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO credential (name, type, username, password) VALUES ('c','password','u',?)")) {
            ps.setString(1, cipher);
            ps.executeUpdate();
        }
        StandardPBEStringEncryptor newEnc = CryptoUtil.buildStrongEncryptor("new-key");
        try (Connection c = DatabaseManager.getConnection()) {
            MigrationRunner.run(c, newEnc);
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password FROM credential WHERE name='c'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("old-value", newEnc.decrypt(rs.getString(1)));
        }
    }

    @Test
    public void testMigrationRollback_onDecryptFailure_leavesDbUntouched() throws Exception {
        // Inject a garbage ciphertext that no legacy encryptor can decrypt.
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO credential (name, type, username, password) VALUES ('c','password','u',?)")) {
            ps.setString(1, "garbage-not-real-ciphertext-xxxxxxxxxxxx");
            ps.executeUpdate();
        }
        StandardPBEStringEncryptor newEnc = CryptoUtil.buildStrongEncryptor("new-key");
        MigrationRunner.MigrationException ex = assertThrows(
            MigrationRunner.MigrationException.class,
            () -> {
                try (Connection c = DatabaseManager.getConnection()) {
                    MigrationRunner.run(c, newEnc);
                }
            });
        assertFalse(ex.getFailedRows().isEmpty());
        // Verify row is unchanged (still garbage).
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password FROM credential WHERE name='c'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("garbage-not-real-ciphertext-xxxxxxxxxxxx", rs.getString(1));
        }
    }
}
