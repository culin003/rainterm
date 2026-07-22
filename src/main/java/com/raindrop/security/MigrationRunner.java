package com.raindrop.security;

import com.raindrop.util.CryptoUtil;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot re-encryption of every encrypted column from the legacy hardcoded
 * key to the master-password-derived key. Called by {@link SecurityManager}
 * during first-time setup. Transactional: all rows re-encrypt or nothing does.
 */
public final class MigrationRunner {

    /** Table + column pair to iterate. */
    private static final String[][] TARGETS = {
        {"connection_profile", "password"},
        {"connection_profile", "key_pass"},
        {"credential", "password"},
        {"credential", "key_data"},
        {"credential", "key_pass"},
    };

    /** Row that could not be decrypted with either legacy encryptor. */
    public record FailedRow(String table, long id, String column) {}

    /** Thrown when at least one row could not be re-encrypted; transaction is rolled back. */
    public static final class MigrationException extends Exception {
        private final List<FailedRow> failed;
        public MigrationException(List<FailedRow> failed) {
            super("Migration failed on " + failed.size() + " row(s)");
            this.failed = failed;
        }
        public List<FailedRow> getFailedRows() { return failed; }
    }

    private MigrationRunner() {}

    /**
     * Run the migration in a single transaction on the supplied connection.
     * Caller owns the Connection lifecycle. Autocommit is toggled off/on here.
     */
    public static void run(Connection conn, StandardPBEStringEncryptor newEncryptor) throws SQLException, MigrationException {
        StandardPBEStringEncryptor strongLegacy = CryptoUtil.buildLegacyStrongEncryptor();
        StandardPBEStringEncryptor desLegacy = CryptoUtil.buildLegacyDesEncryptor();
        List<FailedRow> failed = new ArrayList<>();
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (String[] target : TARGETS) {
                reencryptColumn(conn, target[0], target[1], strongLegacy, desLegacy, newEncryptor, failed);
            }
            if (!failed.isEmpty()) {
                conn.rollback();
                throw new MigrationException(failed);
            }
            conn.commit();
        } catch (SQLException | MigrationException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private static void reencryptColumn(Connection conn, String table, String column,
                                        StandardPBEStringEncryptor strongLegacy,
                                        StandardPBEStringEncryptor desLegacy,
                                        StandardPBEStringEncryptor newEnc,
                                        List<FailedRow> failed) throws SQLException {
        String selectSql = "SELECT id, " + column + " FROM " + table
            + " WHERE " + column + " IS NOT NULL AND " + column + " != ''";
        String updateSql = "UPDATE " + table + " SET " + column + " = ? WHERE id = ?";
        try (PreparedStatement sel = conn.prepareStatement(selectSql);
             ResultSet rs = sel.executeQuery();
             PreparedStatement upd = conn.prepareStatement(updateSql)) {
            int batchSize = 0;
            while (rs.next()) {
                long id = rs.getLong("id");
                String cipher = rs.getString(column);
                String plain = tryDecrypt(cipher, strongLegacy, desLegacy);
                if (plain == null) {
                    failed.add(new FailedRow(table, id, column));
                    continue;
                }
                upd.setString(1, newEnc.encrypt(plain));
                upd.setLong(2, id);
                upd.addBatch();
                batchSize++;
            }
            if (batchSize > 0) upd.executeBatch();
        }
    }

    private static String tryDecrypt(String cipher, StandardPBEStringEncryptor strongLegacy, StandardPBEStringEncryptor desLegacy) {
        try {
            return strongLegacy.decrypt(cipher);
        } catch (EncryptionOperationNotPossibleException ignored) {
            // fall through to DES legacy
        }
        try {
            return desLegacy.decrypt(cipher);
        } catch (EncryptionOperationNotPossibleException ignored) {
            return null;
        }
    }
}
