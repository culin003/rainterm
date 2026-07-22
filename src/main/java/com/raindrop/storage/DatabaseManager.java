package com.raindrop.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    /**
     * JDBC URL override for tests. When the system property
     * {@code raindrop.db.url} is set (e.g. {@code -Draindrop.db.url=jdbc:sqlite:file::memory:?cache=shared}),
     * that URL is used verbatim instead of the on-disk production database.
     * Isolates test runs so they never pollute {@code ~/.raindrop/raindrop.db}.
     */
    private static final String DB_URL_PROPERTY = "raindrop.db.url";
    private static final String DB_DIR = System.getProperty("user.home") + "/.raindrop";
    private static final String DB_PATH = DB_DIR + "/raindrop.db";

    private static volatile boolean schemaInitialized = false;

    /**
     * Return a fresh SQLite connection.
     *
     * <p>Callers should own the connection lifecycle via try-with-resources —
     * this class no longer holds a shared connection singleton. Rationale:
     * <ul>
     *   <li>The old singleton design let any {@code try-with-resources} close
     *       the shared connection, forcing the next caller to re-open it AND
     *       re-run {@code initSchema()} — heavy CPU per config read.</li>
     *   <li>SQLite single-connection is strictly serial for concurrent
     *       statements; sharing one connection across virtual threads risks
     *       {@code SQLITE_BUSY} and interleaved transactions.</li>
     *   <li>SQLite file mode fully supports multi-connection access, so
     *       one-connection-per-operation is the recommended pattern.</li>
     * </ul>
     *
     * <p>Per-connection PRAGMAs applied here:
     * <ul>
     *   <li>{@code journal_mode=WAL} — writers and readers no longer block
     *       each other; needed once multiple connections exist.</li>
     *   <li>{@code busy_timeout=5000} — automatic 5-second wait on lock
     *       contention instead of an immediate {@code SQLITE_BUSY} throw.</li>
     * </ul>
     *
     * <p>Schema initialization runs exactly once per JVM (on the first
     * successful {@code getConnection()}) using its own connection, then the
     * {@code schemaInitialized} flag skips it forever after.
     */
    public static Connection getConnection() throws SQLException {
        String url = resolveUrl();
        ensureSchema(url);
        Connection conn = DriverManager.getConnection(url);
        applyConnectionPragmas(conn);
        return conn;
    }

    private static void applyConnectionPragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static String resolveUrl() {
        String url = System.getProperty(DB_URL_PROPERTY);
        if (url == null || url.isBlank()) {
            new File(DB_DIR).mkdirs();
            url = "jdbc:sqlite:" + DB_PATH;
        }
        return url;
    }

    private static void ensureSchema(String url) throws SQLException {
        if (schemaInitialized) return;
        synchronized (DatabaseManager.class) {
            if (schemaInitialized) return;
            try (Connection init = DriverManager.getConnection(url)) {
                applyConnectionPragmas(init);
                initSchema(init);
            }
            schemaInitialized = true;
        }
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS connection_profile (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER DEFAULT 22,
                    auth_type TEXT DEFAULT 'credential',
                    credential_id INTEGER,
                    username TEXT,
                    password TEXT,
                    key_path TEXT,
                    key_pass TEXT,
                    group_name TEXT DEFAULT '默认',
                    encoding TEXT DEFAULT 'UTF-8',
                    created_at TEXT,
                    updated_at TEXT
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS credential (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    username TEXT NOT NULL,
                    password TEXT,
                    key_data TEXT,
                    key_path TEXT,
                    key_pass TEXT,
                    created_at TEXT,
                    updated_at TEXT
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS app_setting (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """);
        }
    }

    /**
     * Kept for API compatibility with {@code RaindropApp.stop()}. There is
     * no longer a long-lived connection to close; each caller closes its
     * own connection via try-with-resources. This resets the schema flag
     * so a subsequent test run (in the same JVM) re-initializes cleanly.
     */
    public static synchronized void close() {
        schemaInitialized = false;
    }
}
