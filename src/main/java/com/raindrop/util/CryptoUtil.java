package com.raindrop.util;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;

/**
 * Symmetric password encryption for at-rest secrets. The active encryptor is bound
 * to a key derived from the user's master password (see {@code SecurityManager}).
 * Before unlock, {@link #encrypt}/{@link #decrypt} throw {@link IllegalStateException}
 * so accidental writes/reads during the locked state fail loudly.
 */
public class CryptoUtil {
    /** Legacy hardcoded password kept ONLY for one-shot migration of pre-feature data. */
    public static final String LEGACY_SECRET_KEY = "raindrop-secret-key-2024";

    private static volatile StandardPBEStringEncryptor active;

    /** Set the active encryptor. Called by {@code SecurityManager}. */
    public static void setActiveEncryptor(StandardPBEStringEncryptor encryptor) {
        active = encryptor;
    }

    /** Clear the active encryptor. Called on lock. */
    public static void clearActiveEncryptor() {
        active = null;
    }

    public static boolean isUnlocked() {
        return active != null;
    }

    /** Convenience for tests / callers that just want to unlock with a raw password. */
    public static void unlockWithPassword(String password) {
        active = buildStrongEncryptor(password);
    }

    public static StandardPBEStringEncryptor buildStrongEncryptor(String password) {
        StandardPBEStringEncryptor e = new StandardPBEStringEncryptor();
        e.setPassword(password);
        e.setAlgorithm("PBEWithHMACSHA256AndAES_256");
        e.setKeyObtentionIterations(10_000);
        e.setSaltGenerator(new RandomSaltGenerator());
        e.setIvGenerator(new RandomIvGenerator());
        return e;
    }

    /** Legacy strong encryptor (same PBE algo, hardcoded key). Only used by migration. */
    public static StandardPBEStringEncryptor buildLegacyStrongEncryptor() {
        return buildStrongEncryptor(LEGACY_SECRET_KEY);
    }

    /** Ancient PBEWithMD5AndDES encryptor. Only used by migration for oldest ciphertext. */
    public static StandardPBEStringEncryptor buildLegacyDesEncryptor() {
        StandardPBEStringEncryptor e = new StandardPBEStringEncryptor();
        e.setPassword(LEGACY_SECRET_KEY);
        e.setAlgorithm("PBEWithMD5AndDES");
        return e;
    }

    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return plainText;
        StandardPBEStringEncryptor enc = active;
        if (enc == null) throw new IllegalStateException("Crypto is locked");
        return enc.encrypt(plainText);
    }

    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return cipherText;
        StandardPBEStringEncryptor enc = active;
        if (enc == null) throw new IllegalStateException("Crypto is locked");
        return enc.decrypt(cipherText);
    }
}
