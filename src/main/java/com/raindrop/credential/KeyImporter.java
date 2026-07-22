package com.raindrop.credential;

import com.raindrop.core.KeyLoader;
import com.raindrop.util.CryptoUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Imports SSH key files and stores them in the credential manager.
 */
public class KeyImporter {

    /**
     * Import a key file and create a credential entry.
     * @param name Display name for the credential
     * @param keyPath Path to the private key file
     * @param passphrase Optional passphrase
     * @param username SSH username
     * @return The created CredentialEntry
     */
    public static CredentialEntry importKeyFile(String name, String keyPath, String passphrase, String username) throws IOException {
        if (!KeyLoader.isValidKeyFile(keyPath)) {
            throw new IOException("Invalid key file: " + keyPath);
        }

        // Read and encrypt the key content
        String keyContent = Files.readString(Path.of(keyPath));
        String encryptedKey = CryptoUtil.encrypt(keyContent);

        // Create credential entry
        CredentialEntry entry = new CredentialEntry(name, "key", username);
        entry.setKeyData(encryptedKey);
        entry.setKeyPath(keyPath);
        if (passphrase != null && !passphrase.isEmpty()) {
            entry.setKeyPass(CryptoUtil.encrypt(passphrase));
        }

        return entry;
    }

    /**
     * Validate that a key file can be loaded.
     */
    public static boolean validateKey(String keyPath, String passphrase) {
        return KeyLoader.validateKey(keyPath, passphrase);
    }

    /**
     * Get key info for display.
     */
    public static String getKeyInfo(String keyPath) {
        try {
            String type = KeyLoader.getKeyType(keyPath);
            return type + " key: " + keyPath;
        } catch (IOException e) {
            return "Unknown key: " + keyPath;
        }
    }
}
