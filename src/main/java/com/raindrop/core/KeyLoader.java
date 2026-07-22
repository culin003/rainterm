package com.raindrop.core;

import net.schmizz.sshj.SSHClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and validates SSH private keys from files.
 */
public class KeyLoader {

    /**
     * Read the first non-blank line of a text file without loading the whole
     * file into memory. Users occasionally point the "key file" picker at a
     * multi-MB file; {@code Files.readAllLines} would then allocate all of it
     * only to look at the first line.
     */
    private static String firstNonBlankLine(String path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) return line;
            }
        }
        return "";
    }

    /**
     * Check if a key file is valid and can be loaded.
     */
    public static boolean isValidKeyFile(String keyPath) {
        try {
            File file = new File(keyPath);
            if (!file.exists() || !file.canRead()) {
                return false;
            }
            String firstLine = firstNonBlankLine(keyPath);
            return firstLine.startsWith("-----BEGIN") ||
                   firstLine.startsWith("ssh-rsa") ||
                   firstLine.startsWith("ssh-ed25519") ||
                   firstLine.startsWith("ecdsa-sha2");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Validate that a key file can be loaded by SSHJ.
     */
    public static boolean validateKey(String keyPath, String passphrase) {
        SSHClient client = new SSHClient();
        try {
            if (passphrase != null && !passphrase.isEmpty()) {
                client.loadKeys(keyPath, passphrase);
            } else {
                client.loadKeys(keyPath);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Get the key type from a key file. Recognises the standard PEM headers
     * and the OpenSSH-format key header used by ssh-keygen since ~2014.
     *
     * <p>Uses precise {@code startsWith} on the PEM begin line — the older
     * {@code contains("RSA")} check false-matched anything with "RSA" anywhere
     * in the line and mis-classified OpenSSH-format RSA keys (whose header
     * says "OPENSSH PRIVATE KEY") as unknown when RSA content was later.
     */
    public static String getKeyType(String keyPath) throws IOException {
        String firstLine = firstNonBlankLine(keyPath);
        // Order matters: check OpenSSH before RSA/DSA/EC because
        // an OpenSSH-format key never carries the algorithm in its header.
        if (firstLine.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) return "OPENSSH";
        if (firstLine.startsWith("-----BEGIN RSA PRIVATE KEY-----")) return "RSA";
        if (firstLine.startsWith("-----BEGIN DSA PRIVATE KEY-----")) return "DSA";
        if (firstLine.startsWith("-----BEGIN EC PRIVATE KEY-----")) return "EC";
        if (firstLine.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----")) return "PKCS8-ENCRYPTED";
        if (firstLine.startsWith("-----BEGIN PRIVATE KEY-----")) return "PKCS8";
        if (firstLine.startsWith("---- BEGIN SSH2 PRIVATE KEY ----")) return "SSH2";
        // Public-key single-line formats (ssh-rsa AAAA..., ssh-ed25519 AAAA..., etc.)
        if (firstLine.startsWith("ssh-rsa")) return "RSA";
        if (firstLine.startsWith("ssh-ed25519")) return "ED25519";
        if (firstLine.startsWith("ecdsa-sha2")) return "ECDSA";
        return "UNKNOWN";
    }
}
