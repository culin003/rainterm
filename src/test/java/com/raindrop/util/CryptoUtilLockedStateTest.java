package com.raindrop.util;

import com.raindrop.security.SecurityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CryptoUtilLockedStateTest {

    @BeforeEach
    public void resetLocked() {
        SecurityManager.resetForTests();
        CryptoUtil.clearActiveEncryptor();
    }

    @AfterEach
    public void restoreForOtherTests() {
        // Leave a valid encryptor so subsequent tests in this JVM don't fail.
        CryptoUtil.unlockWithPassword("test-master-password");
    }

    @Test
    public void testEncryptWhileLockedThrows() {
        assertFalse(CryptoUtil.isUnlocked());
        assertThrows(IllegalStateException.class, () -> CryptoUtil.encrypt("data"));
    }

    @Test
    public void testDecryptWhileLockedThrows() {
        assertFalse(CryptoUtil.isUnlocked());
        assertThrows(IllegalStateException.class, () -> CryptoUtil.decrypt("cipher"));
    }

    @Test
    public void testRoundTripAfterUnlock() {
        CryptoUtil.unlockWithPassword("some-password");
        assertTrue(CryptoUtil.isUnlocked());
        String enc = CryptoUtil.encrypt("plain");
        assertNotEquals("plain", enc);
        assertEquals("plain", CryptoUtil.decrypt(enc));
    }

    @Test
    public void testNullAndEmptyAllowedWhenLocked() {
        // Null/empty passthrough must not fail even when locked — many callers
        // pass "" for absent optional secrets.
        assertNull(CryptoUtil.encrypt(null));
        assertEquals("", CryptoUtil.encrypt(""));
        assertNull(CryptoUtil.decrypt(null));
        assertEquals("", CryptoUtil.decrypt(""));
    }

    @Test
    public void testLegacyEncryptorHelperProducesLegacyDecryptable() {
        org.jasypt.encryption.pbe.StandardPBEStringEncryptor des = CryptoUtil.buildLegacyDesEncryptor();
        String cipher = des.encrypt("hello");
        assertEquals("hello", des.decrypt(cipher));
    }
}
