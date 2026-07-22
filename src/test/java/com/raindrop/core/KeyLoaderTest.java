package com.raindrop.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Non-network tests for {@link KeyLoader}. We exercise the file-shape detection
 * (isValidKeyFile / getKeyType) with synthetic PEM-looking files and skip the
 * network-dependent validateKey path.
 */
public class KeyLoaderTest {

    @Test
    public void testIsValidKeyFileMissing() {
        assertFalse(KeyLoader.isValidKeyFile("/no/such/path/should/exist.pem"));
    }

    @Test
    public void testIsValidKeyFileOpenSshHeader(@TempDir Path dir) throws IOException {
        Path key = dir.resolve("id_ed25519");
        Files.writeString(key,
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "dummy-body\n" +
            "-----END OPENSSH PRIVATE KEY-----\n");
        assertTrue(KeyLoader.isValidKeyFile(key.toString()));
    }

    @Test
    public void testIsValidKeyFileRsaHeader(@TempDir Path dir) throws IOException {
        Path key = dir.resolve("id_rsa");
        Files.writeString(key,
            "-----BEGIN RSA PRIVATE KEY-----\n" +
            "dummy-body\n" +
            "-----END RSA PRIVATE KEY-----\n");
        assertTrue(KeyLoader.isValidKeyFile(key.toString()));
    }

    @Test
    public void testIsValidKeyFileRejectsRandomText(@TempDir Path dir) throws IOException {
        Path notAKey = dir.resolve("readme.txt");
        Files.writeString(notAKey, "Hello world\nThis is not a key.\n");
        assertFalse(KeyLoader.isValidKeyFile(notAKey.toString()));
    }

    @Test
    public void testGetKeyTypeRsa(@TempDir Path dir) throws IOException {
        Path key = dir.resolve("id_rsa");
        Files.writeString(key, "-----BEGIN RSA PRIVATE KEY-----\n");
        assertEquals("RSA", KeyLoader.getKeyType(key.toString()));
    }

    @Test
    public void testGetKeyTypeOpenssh(@TempDir Path dir) throws IOException {
        Path key = dir.resolve("id_ed25519");
        Files.writeString(key, "-----BEGIN OPENSSH PRIVATE KEY-----\n");
        assertEquals("OPENSSH", KeyLoader.getKeyType(key.toString()));
    }
}
