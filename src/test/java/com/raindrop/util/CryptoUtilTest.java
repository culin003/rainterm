package com.raindrop.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CryptoUtilTest {

    @BeforeAll
    public static void unlock() {
        CryptoUtil.unlockWithPassword("test-master-password");
    }

    @Test
    public void testEncryptDecrypt() {
        String plainText = "my-secret-password-123";
        String encrypted = CryptoUtil.encrypt(plainText);
        String decrypted = CryptoUtil.decrypt(encrypted);

        assertNotEquals(plainText, encrypted);
        assertEquals(plainText, decrypted);
    }

    @Test
    public void testEncryptNull() {
        assertNull(CryptoUtil.encrypt(null));
    }

    @Test
    public void testDecryptNull() {
        assertNull(CryptoUtil.decrypt(null));
    }

    @Test
    public void testEncryptEmpty() {
        assertEquals("", CryptoUtil.encrypt(""));
    }

    @Test
    public void testDecryptEmpty() {
        assertEquals("", CryptoUtil.decrypt(""));
    }

    @Test
    public void testEncryptChinese() {
        String plainText = "密码测试123";
        String encrypted = CryptoUtil.encrypt(plainText);
        String decrypted = CryptoUtil.decrypt(encrypted);
        assertEquals(plainText, decrypted);
    }

    @Test
    public void testDifferentEncryptions() {
        String plainText = "same-password";
        String encrypted1 = CryptoUtil.encrypt(plainText);
        String encrypted2 = CryptoUtil.encrypt(plainText);

        assertNotEquals(encrypted1, encrypted2);
        assertEquals(plainText, CryptoUtil.decrypt(encrypted1));
        assertEquals(plainText, CryptoUtil.decrypt(encrypted2));
    }
}
