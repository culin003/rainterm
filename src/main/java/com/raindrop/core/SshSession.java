package com.raindrop.core;

import com.raindrop.credential.CredentialEntry;
import com.raindrop.credential.CredentialManager;
import com.raindrop.storage.ConnectionProfile;
import com.raindrop.util.CryptoUtil;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SshSession {
    private volatile SSHClient client;
    private volatile Session.Shell shell;
    private volatile SFTPClient sftpClient;
    private volatile boolean connected = false;
    private volatile boolean disconnectRequested = false;
    private final ConnectionProfile profile;
    private volatile java.nio.file.Path tempKeyFile;

    public SshSession(ConnectionProfile profile) {
        this.profile = profile;
    }

    public void connect() throws IOException {
        if (disconnectRequested) {
            throw new IOException("Connection cancelled by user");
        }

        try {
            client = new SSHClient();
            client.addHostKeyVerifier(new PromiscuousVerifier());

            if (disconnectRequested) {
                throw new IOException("Connection cancelled by user");
            }

            client.connect(profile.getHost(), profile.getPort());

            if (disconnectRequested) {
                throw new IOException("Connection cancelled by user");
            }

            AuthMaterial auth = resolveAuthMaterial();
            if (auth.password != null) {
                client.authPassword(auth.username, auth.password);
            } else if (auth.keyProvider != null) {
                client.authPublickey(auth.username, auth.keyProvider);
            } else {
                throw new IOException("No authentication method provided");
            }

            if (disconnectRequested) {
                throw new IOException("Connection cancelled by user");
            }

            Session session = client.startSession();
            session.allocateDefaultPTY();
            shell = session.startShell();
            connected = true;
        } catch (IOException e) {
            // Clean up SSHClient, temp key file, and any partial session on failure
            disconnect();
            throw e;
        }
    }

    /**
     * Resolve the actual auth material for this profile. Supports three auth_type values:
     * <ul>
     *   <li>{@code credential} — look up {@link #profile}.credentialId in the credential table</li>
     *   <li>{@code key_inline} — use profile.keyPath / profile.keyPass (encrypted)</li>
     *   <li>{@code password_inline} — use profile.password (encrypted)</li>
     * </ul>
     * Legacy profiles (no auth_type) fall back to the historical behaviour: password if set,
     * otherwise keyPath.
     */
    private AuthMaterial resolveAuthMaterial() throws IOException {
        String type = profile.getAuthType();
        if (type == null || type.isEmpty()) {
            type = profile.getPassword() != null && !profile.getPassword().isEmpty() ? "password_inline" : "key_inline";
        }
        switch (type) {
            case "credential":
                return resolveFromCredential();
            case "key_inline":
                return resolveFromInlineKey();
            case "password_inline":
            default:
                return resolveFromInlinePassword();
        }
    }

    private AuthMaterial resolveFromInlinePassword() {
        AuthMaterial a = new AuthMaterial();
        a.username = profile.getUsername();
        if (profile.getPassword() != null && !profile.getPassword().isEmpty()) {
            a.password = CryptoUtil.decrypt(profile.getPassword());
        }
        return a;
    }

    private AuthMaterial resolveFromInlineKey() throws IOException {
        AuthMaterial a = new AuthMaterial();
        a.username = profile.getUsername();
        if (profile.getKeyPath() == null || profile.getKeyPath().isEmpty()) return a;
        String keyPass = profile.getKeyPass() != null && !profile.getKeyPass().isEmpty()
            ? CryptoUtil.decrypt(profile.getKeyPass()) : null;
        a.keyProvider = keyPass != null
            ? client.loadKeys(profile.getKeyPath(), keyPass.toCharArray())
            : client.loadKeys(profile.getKeyPath());
        return a;
    }

    private AuthMaterial resolveFromCredential() throws IOException {
        if (profile.getCredentialId() == null) {
            throw new IOException("auth_type=credential but no credential_id set");
        }
        CredentialEntry entry;
        try {
            entry = new CredentialManager().findById(profile.getCredentialId());
        } catch (java.sql.SQLException e) {
            throw new IOException("Failed to load credential: " + e.getMessage(), e);
        }
        if (entry == null) throw new IOException("Credential id=" + profile.getCredentialId() + " not found");

        AuthMaterial a = new AuthMaterial();
        // Credential owns the username; fall back to profile username if unset.
        a.username = entry.getUsername() != null && !entry.getUsername().isEmpty()
            ? entry.getUsername() : profile.getUsername();

        if ("password".equalsIgnoreCase(entry.getType())) {
            if (entry.getPassword() != null && !entry.getPassword().isEmpty()) {
                a.password = CryptoUtil.decrypt(entry.getPassword());
            }
        } else if ("key".equalsIgnoreCase(entry.getType())) {
            String keyPass = entry.getKeyPass() != null && !entry.getKeyPass().isEmpty()
                ? CryptoUtil.decrypt(entry.getKeyPass()) : null;
            if (entry.getKeyData() != null && !entry.getKeyData().isEmpty()) {
                // Materialize the decrypted key to a temp file — SSHJ's loadKeys(path,...) does
                // the right OpenSSH/PKCS8 auto-detection. Chmod 600 so SSHJ doesn't complain.
                String pem = CryptoUtil.decrypt(entry.getKeyData());
                Path tmp = Files.createTempFile("raindrop-key-", ".pem");
                Files.writeString(tmp, pem);
                try {
                    Files.setPosixFilePermissions(tmp, java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                    // Windows: skip POSIX perms
                }
                this.tempKeyFile = tmp;
                a.keyProvider = keyPass != null
                    ? client.loadKeys(tmp.toString(), keyPass.toCharArray())
                    : client.loadKeys(tmp.toString());
            } else if (entry.getKeyPath() != null && !entry.getKeyPath().isEmpty()) {
                a.keyProvider = keyPass != null
                    ? client.loadKeys(entry.getKeyPath(), keyPass.toCharArray())
                    : client.loadKeys(entry.getKeyPath());
            }
        }
        return a;
    }

    private static final class AuthMaterial {
        String username;
        String password;
        KeyProvider keyProvider;
    }

    public void write(String command) throws IOException {
        if (shell != null) {
            OutputStream out = shell.getOutputStream();
            out.write(command.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    public void disconnect() {
        disconnectRequested = true;
        connected = false;
        try {
            if (sftpClient != null) sftpClient.close();
        } catch (Exception ignored) {}
        sftpClient = null;
        try {
            if (shell != null) shell.close();
        } catch (Exception ignored) {}
        shell = null;
        try {
            if (client != null) client.disconnect();
        } catch (IOException ignored) {}
        client = null;
        // Delete temporary key file if it exists
        if (tempKeyFile != null) {
            try {
                java.nio.file.Files.delete(tempKeyFile);
            } catch (Exception ignored) {}
            tempKeyFile = null;
        }
    }

    /**
     * Return a lazily-created, cached {@link SFTPClient} bound to this session.
     *
     * <p>Previously {@code SftpService} opened a fresh SFTP subsystem channel
     * on every operation (list / mkdir / upload …), paying one full SSH
     * channel-open + handshake round-trip per call. For a file-browser UI
     * that emits several {@code ls} per second while the user clicks around,
     * that overhead is easily observable.
     *
     * <p>SSHJ's {@link SFTPClient} is thread-safe and designed for reuse;
     * this method returns the same instance for the lifetime of the session
     * and closes it in {@link #disconnect()}. On failure to create the
     * client the field stays null so the next call retries.
     */
    public SFTPClient getSftpClient() throws IOException {
        SFTPClient local = sftpClient;
        if (local != null) return local;
        synchronized (this) {
            if (sftpClient == null) {
                if (client == null || !client.isConnected()) {
                    throw new IOException("SSH client is not connected");
                }
                sftpClient = client.newSFTPClient();
            }
            return sftpClient;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public ConnectionProfile getProfile() {
        return profile;
    }

    public SSHClient getClient() {
        return client;
    }

    public Session.Shell getShell() {
        return shell;
    }
}

