package com.raindrop.core;

import com.raindrop.storage.ConnectionProfile;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of active SSH sessions, keyed by an opaque owner token
 * (typically a Tab, but the manager stays UI-agnostic).
 *
 * <p>Owns three responsibilities:
 * <ul>
 *   <li>Track which sessions are live so {@link #disconnectAll()} can hit them all.</li>
 *   <li>Look up the {@link SshSession} attached to a given owner.</li>
 *   <li>Reconnect a session under the same owner, replacing the old one.</li>
 * </ul>
 */
public class ConnectionManager {
    private final Map<Object, SshSession> byOwner = new ConcurrentHashMap<>();

    /**
     * Convenience helper for callers that don't need owner-based lookup.
     * Creates, connects, and registers under an auto-generated key.
     */
    public SshSession createSession(ConnectionProfile profile) throws IOException {
        SshSession session = new SshSession(profile);
        session.connect();
        register(new Object(), session);
        return session;
    }

    /** Register a session under an owner token. Replaces any prior registration. */
    public void register(Object owner, SshSession session) {
        byOwner.put(owner, session);
    }

    /** Unregister and return the previously-registered session (if any). */
    public SshSession unregister(Object owner) {
        return byOwner.remove(owner);
    }

    public SshSession getSession(Object owner) {
        return byOwner.get(owner);
    }

    /**
     * Close the existing session for {@code owner} (if any), connect a fresh one
     * against the same profile, and register it under the same owner.
     */
    public SshSession reconnect(Object owner) throws IOException {
        SshSession old = byOwner.remove(owner);
        if (old == null) throw new IOException("No session registered for owner " + owner);
        ConnectionProfile profile = old.getProfile();
        try { old.disconnect(); } catch (Exception ignored) {}
        SshSession fresh = new SshSession(profile);
        fresh.connect();
        byOwner.put(owner, fresh);
        return fresh;
    }

    public void disconnectAll() {
        for (SshSession s : byOwner.values()) {
            try { s.disconnect(); } catch (Exception ignored) {}
        }
        byOwner.clear();
    }

    public int getActiveCount() {
        return byOwner.size();
    }

    public Set<SshSession> getSessions() {
        return Collections.unmodifiableSet(Set.copyOf(byOwner.values()));
    }
}
