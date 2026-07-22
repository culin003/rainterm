package com.raindrop.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Derives the Jasypt PBE password from the user's master password.
 * v1 = SHA-256(master + fixed app pepper) → Base64. Bumping the KDF version
 * lets future upgrades trigger a re-encryption migration transparently.
 */
public final class PasswordKdf {
    public static final int CURRENT_VERSION = 1;
    private static final String PEPPER = "raindrop-app-pepper-v1";

    private PasswordKdf() {}

    public static String deriveJasyptPassword(String master, int version) {
        if (version != 1) throw new IllegalArgumentException("Unsupported KDF version: " + version);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(master.getBytes(StandardCharsets.UTF_8));
            md.update(PEPPER.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
