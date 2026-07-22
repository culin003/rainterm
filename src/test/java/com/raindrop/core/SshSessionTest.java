package com.raindrop.core;

import com.raindrop.storage.ConnectionProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SshSession.
 * Note: These tests require network access to the test server.
 */
public class SshSessionTest {

    @BeforeAll
    public static void unlockCrypto() {
        com.raindrop.util.CryptoUtil.unlockWithPassword("test-master-password");
    }

    private ConnectionProfile createTestProfile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName("Test Server");
        profile.setHost("192.168.1.161");
        profile.setPort(35922);
        profile.setUsername("nubomed");
        profile.setPassword(com.raindrop.util.CryptoUtil.encrypt("Nb@112233"));
        profile.setAuthType("password_inline");
        return profile;
    }

    @Test
    public void testConnectAndDisconnect() throws Exception {
        ConnectionProfile profile = createTestProfile();
        SshSession session = new SshSession(profile);

        try {
            session.connect();
            assertTrue(session.isConnected());
        } finally {
            session.disconnect();
            assertFalse(session.isConnected());
        }
    }

    @Test
    public void testWriteCommand() throws Exception {
        ConnectionProfile profile = createTestProfile();
        SshSession session = new SshSession(profile);

        try {
            session.connect();
            assertTrue(session.isConnected());

            // Write a simple command
            session.write("echo test\r");

            // Wait a bit for the command to be sent
            Thread.sleep(500);
            assertTrue(session.isConnected());
        } finally {
            session.disconnect();
        }
    }

    @Test
    public void testReadOutput() throws Exception {
        ConnectionProfile profile = createTestProfile();
        SshSession session = new SshSession(profile);
        AtomicBoolean receivedOutput = new AtomicBoolean(false);

        try {
            session.connect();
            assertNotNull(session.getShell());

            CountDownLatch latch = new CountDownLatch(1);
            Thread readerThread = new Thread(() -> {
                try {
                    InputStream in = session.getShell().getInputStream();
                    byte[] buf = new byte[4096];
                    int n = in.read(buf);
                    if (n > 0) receivedOutput.set(true);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            Thread.sleep(1000);
            session.write("echo hello-from-test\r");
            Thread.sleep(2000);
            assertTrue(session.isConnected());
        } finally {
            session.disconnect();
        }
    }

    @Test
    public void testGetProfile() {
        ConnectionProfile profile = createTestProfile();
        SshSession session = new SshSession(profile);

        assertNotNull(session.getProfile());
        assertEquals("192.168.1.161", session.getProfile().getHost());
        assertEquals(35922, session.getProfile().getPort());
    }
}
