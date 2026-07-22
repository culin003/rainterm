package com.raindrop.security;

import com.raindrop.storage.DatabaseManager;
import com.raindrop.util.ConfigManager;
import com.raindrop.util.CryptoUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class IdleWatchdogTest {

    @BeforeEach
    public void setUp() throws Exception {
        SecurityManager.resetForTests();
        try (Connection c = DatabaseManager.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM app_setting");
            st.executeUpdate("DELETE FROM credential");
            st.executeUpdate("DELETE FROM connection_profile");
        }
        // Purge ConfigManager cache so stale verifier entries don't fool bootstrap().
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_VERIFIER);
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_HINT);
        ConfigManager.getInstance().remove(ConfigManager.KEY_MASTER_PASSWORD_KDF_VERSION);
        ConfigManager.getInstance().remove(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS);
        // Fresh watchdog state: bump lastActivity to now.
        IdleWatchdog.get().markActivity();
    }

    @AfterEach
    public void tearDown() {
        SecurityManager.resetForTests();
        CryptoUtil.unlockWithPassword("test-master-password");
    }

    @Test
    public void testTimeoutZeroDisablesLock() throws Exception {
        // Setup an unlocked SecurityManager.
        SecurityManager.getInstance().bootstrap();
        SecurityManager.getInstance().completeSetup("pw", "");
        ConfigManager.getInstance().set(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, "0");
        // Simulate a very stale lastActivity.
        IdleWatchdog.get().setLastActivityForTest(0L);
        IdleWatchdog.get().tick(System.nanoTime());
        assertFalse(SecurityManager.getInstance().isLocked());
    }

    @Test
    public void testTimeoutFires_afterElapsed() throws Exception {
        SecurityManager.getInstance().bootstrap();
        SecurityManager.getInstance().completeSetup("pw", "");
        ConfigManager.getInstance().set(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, "1");
        long now = System.nanoTime();
        IdleWatchdog.get().setLastActivityForTest(now - 5_000_000_000L); // 5 s idle
        IdleWatchdog.get().tick(now);
        assertTrue(SecurityManager.getInstance().isLocked());
    }

    @Test
    public void testMarkActivityResetsTimer() throws Exception {
        SecurityManager.getInstance().bootstrap();
        SecurityManager.getInstance().completeSetup("pw", "");
        ConfigManager.getInstance().set(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, "60");
        // Old idle, then activity, then tick — should NOT lock.
        IdleWatchdog.get().setLastActivityForTest(System.nanoTime() - 120_000_000_000L);
        IdleWatchdog.get().markActivity();
        IdleWatchdog.get().tick(System.nanoTime());
        assertFalse(SecurityManager.getInstance().isLocked());
    }

    @Test
    public void testLockedStateSkipsTick() throws Exception {
        SecurityManager.getInstance().bootstrap();
        SecurityManager.getInstance().completeSetup("pw", "");
        SecurityManager.getInstance().lock();
        assertTrue(SecurityManager.getInstance().isLocked());
        ConfigManager.getInstance().set(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, "1");
        IdleWatchdog.get().setLastActivityForTest(0L);
        // Should be a no-op — already locked.
        IdleWatchdog.get().tick(System.nanoTime());
        assertTrue(SecurityManager.getInstance().isLocked());
    }
}
