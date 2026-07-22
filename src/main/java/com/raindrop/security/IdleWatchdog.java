package com.raindrop.security;

import com.raindrop.util.ConfigManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the timestamp of the last user activity and, on a fixed cadence,
 * checks whether the idle threshold from {@link ConfigManager} has been
 * exceeded. When exceeded (and the app is currently unlocked), asks
 * {@link SecurityManager} to lock.
 *
 * <p>Timeout is re-read from config on every tick so a Settings change
 * applies live without restart. {@code timeout == 0} disables auto-lock.
 */
public final class IdleWatchdog {
    private static volatile IdleWatchdog instance;

    public static IdleWatchdog get() {
        IdleWatchdog local = instance;
        if (local != null) return local;
        synchronized (IdleWatchdog.class) {
            if (instance == null) instance = new IdleWatchdog();
            return instance;
        }
    }

    private final AtomicLong lastActivityNs = new AtomicLong(System.nanoTime());
    private Timeline timeline;

    private IdleWatchdog() {}

    public void markActivity() {
        lastActivityNs.lazySet(System.nanoTime());
    }

    /** Start the FX Timeline. Idempotent. Cadence is 5 s. */
    public void start() {
        if (timeline != null) return;
        timeline = new Timeline(new KeyFrame(Duration.seconds(5), e -> tick(System.nanoTime())));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    public void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }

    /** Package-visible for unit tests. Same logic as the Timeline callback. */
    void tick(long nowNs) {
        int timeoutSec = ConfigManager.getInstance().getInt(ConfigManager.KEY_AUTO_LOCK_TIMEOUT_SECONDS, 600);
        if (timeoutSec <= 0) return;
        SecurityManager sm = SecurityManager.getInstance();
        if (sm.isLocked()) return;
        long idleNs = nowNs - lastActivityNs.get();
        if (idleNs >= timeoutSec * 1_000_000_000L) {
            sm.lock();
        }
    }

    /** Package-visible for tests. */
    long getLastActivityNs() { return lastActivityNs.get(); }
    void setLastActivityForTest(long ns) { lastActivityNs.set(ns); }
}
