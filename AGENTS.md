# AI-Assisted Development Guide for Raindrop

This document helps AI coding assistants (Claude Code, MiMoCode, Cursor, Copilot, etc.) understand the Raindrop project and extend it effectively. It covers architecture patterns, extension points, and step-by-step recipes for common feature additions.

> **For human readers**: If you use an AI assistant to develop Raindrop, keep this file and `CLAUDE.md` in the project root. Most AI tools auto-load these as context.

## 1. Project Quick Reference

| Item | Value |
|------|-------|
| Language | Java 21 LTS |
| GUI | JavaFX 21 + FXML |
| SSH/SFTP | SSHJ 0.40.0 (`com.hierynomus:sshj`) |
| Terminal | JediTermFX 1.1.0 (Canvas-based xterm) |
| Database | SQLite 3.42.0.0 (embedded) |
| Encryption | Jasypt 1.9.3 |
| Build | Gradle 8.5 Kotlin DSL |
| License | Apache 2.0 |

### Key Rules

- All I/O runs on **virtual threads** via `TaskExecutor` — never `Executors.newFixedThreadPool()`
- Never return `null` — use `Optional`
- Never catch and swallow exceptions silently — log or notify UI
- Methods under 30 lines — extract helpers
- UI updates go through `Platform.runLater()`
- Tests must not touch `~/.raindrop/raindrop.db`
- **Modal dialogs**: Do NOT use `initOwner() + initModality()` — it breaks Linux WM maximize. Use `StageStyle.UTILITY + setAlwaysOnTop(true)` + `mainRoot.setDisable(true)`
- **File reading**: Never use `Files.readAllLines()` for potentially large files — use `BufferedReader.readLine()`

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                   JavaFX Application Thread                  │
│                                                              │
│  MainController ──┬── TabManager ── TerminalTab (JediTerm)  │
│                   ├── SftpBrowserController                  │
│                   ├── ConnectionDialogController             │
│                   ├── CredentialDialogController             │
│                   ├── SettingsView                           │
│                   └── LockView / MasterPasswordSetupView     │
│                                                              │
│  ConfigManager (singleton, ConcurrentHashMap cache)          │
└──────────────────────┬──────────────────────────────────────┘
                       │  Platform.runLater()
┌──────────────────────┴──────────────────────────────────────┐
│                    Virtual Threads (I/O)                     │
│                                                              │
│  TaskExecutor ── SshSession ── SSHClient (SSHJ)             │
│                   │                                          │
│                   ├── shell (InputStream/OutputStream)       │
│                   └── SFTPClient (lazy, cached)             │
│                                                              │
│  SftpService ─── receives SshSession (not SSHClient)        │
│                                                              │
│  ConnectionManager ─── tracks active sessions by profile ID  │
└──────────────────────────────────────────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────────────────┐
│                    Persistence Layer                          │
│                                                              │
│  DatabaseManager.getConnection() → new Connection per call   │
│  ProfileRepository ─── connection_profile CRUD               │
│  CredentialManager ─── credential CRUD + encryption          │
│  ConfigManager ─── app_setting (key-value, cached)           │
│                                                              │
│  SQLite WAL mode + busy_timeout=5000ms                       │
└──────────────────────────────────────────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────────────────┐
│                    Security Layer                            │
│                                                              │
│  SecurityManager ─── master password lifecycle               │
│    State: UNINITIALIZED → LOCKED → UNLOCKED                  │
│    Controls CryptoUtil.setActiveEncryptor()                  │
│                                                              │
│  PasswordKdf ─── PBKDF2 for master password verification    │
│  IdleWatchdog ── auto-lock on inactivity                     │
│  MigrationRunner ── re-encrypt old data with new key         │
└──────────────────────────────────────────────────────────────┘
```

### Data Flow

```
User clicks "Connect"
  → MainController.openConnection(profile)
    → TaskExecutor.submit(() -> {            ← virtual thread
        SshSession.connect(profile)
        SshSession.startReading(emulator)   ← virtual thread reads SSH output
        Platform.runLater(() -> tab.setConnected(true))
      })

User types in terminal
  → JediTerm key handler
    → TaskExecutor.submit(() -> session.write(input))  ← virtual thread writes

User opens SFTP browser
  → SftpBrowserController
    → SftpService.listDirectory(session, path)  ← virtual thread
      → session.getSftpClient().ls(path)        ← reuses cached SFTPClient
```

## 3. Extension Points

### 3.1 Adding a New Feature Module

New features typically live as a new package under `com.raindrop`:

```
src/main/java/com/raindrop/
├── core/           # (existing) SSH, SFTP
├── terminal/       # (existing) terminal emulation
├── credential/     # (existing) credential management
├── storage/        # (existing) database
├── ui/             # (existing) JavaFX controllers
├── util/           # (existing) utilities
├── security/       # (existing) master password, idle lock
└── myfeature/      # ← YOUR NEW MODULE
    ├── MyService.java
    ├── MyRepository.java
    └── MyPojo.java
```

**Pattern**: Each module follows `Service` (business logic) + `Repository` (DB access) + `Pojo` (data class).

### 3.2 Adding a New UI Tab/View

1. Create FXML in `src/main/resources/fxml/MyView.fxml`
2. Create controller in `src/main/java/com/raindrop/ui/MyViewController.java`
3. Wire it up in `TabManager` or `MainController`:

```java
// In MainController or TabManager
FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MyView.fxml"));
loader.setController(new MyViewController());
Parent view = loader.load();
tab.setContent(view);
```

### 3.3 Adding a New Setting

1. Add a key constant to `ConfigManager`:

```java
public static final String KEY_MY_FEATURE = "my_feature_enabled";
```

2. Read it anywhere:

```java
boolean enabled = ConfigManager.getInstance().getBoolean(ConfigManager.KEY_MY_FEATURE, false);
```

3. Set it from a UI controller:

```java
ConfigManager.getInstance().set(ConfigManager.KEY_MY_FEATURE, "true");
```

### 3.4 Adding SSH Command Execution

Run a command on a connected session:

```java
TaskExecutor.submit(() -> {
    try {
        Session session = sshClient.startSession();
        session.exec("df -h");
        String output = IOUtils.readFully(session.getInputStream()).toString();
        session.close();
        TaskExecutor.runOnFx(() -> resultArea.setText(output));
    } catch (IOException e) {
        TaskExecutor.runOnFx(() -> showError(e.getMessage()));
    }
});
```

### 3.5 Adding a New Database Table

1. Add the CREATE TABLE to `DatabaseManager.initSchema()`:

```java
stmt.execute("""
    CREATE TABLE IF NOT EXISTS my_table (
        id   INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        data TEXT
    )
""");
```

2. Create a Repository class following `ProfileRepository` pattern:

```java
public class MyRepository {
    public long save(MyPojo pojo) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO my_table (name, data) VALUES (?, ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, pojo.name());
            ps.setString(2, pojo.data());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
```

### 3.6 Adding a New Theme

1. Create CSS in `src/main/resources/css/mytheme.css`
2. The theme name is stored via `ConfigManager.KEY_TERMINAL_THEME`
3. Load in the UI:

```css
/* src/main/resources/css/mytheme.css */
.terminal-pane {
    -fx-background-color: #282a36;
    -fx-text-fill: #f8f8f2;
}
```

### 3.7 Adding Internationalization

1. Add keys to all three JSON files:
   - `src/main/resources/i18n/messages_en_US.json`
   - `src/main/resources/i18n/messages_zh_CN.json`
   - `src/main/resources/i18n/messages_zh_TW.json`

2. Use in FXML via `%key` prefix or in Java via the i18n manager.

### 3.8 Adding a Security Feature

The `security/` package owns the master-password lifecycle:

```
SecurityManager (singleton)
  ├── State: UNINITIALIZED → LOCKED → UNLOCKED
  ├── Controls CryptoUtil.setActiveEncryptor()
  ├── Uses PasswordKdf for PBKDF2 verification
  └── Triggers MigrationRunner for re-encryption on key change
```

To add a security-related feature (e.g., biometric unlock, key rotation):

1. Add logic to `SecurityManager` or create a new class in `security/`
2. UI goes in `ui/security/` (follow `LockController` / `MasterPasswordSetupController` pattern)
3. State changes must happen on FX thread; non-FX threads poll `isLocked()`
4. Always go through `CryptoUtil.setActiveEncryptor()` / `clearActiveEncryptor()` — never set the encryptor directly

## 4. Common AI-Assisted Tasks

### 4.1 "Add a new connection profile field"

**Files to touch**:
1. `ConnectionProfile.java` — add field + getter/setter
2. `DatabaseManager.java` — add column to `connection_profile` table (with migration)
3. `ProfileRepository.java` — update INSERT/UPDATE/SELECT queries
4. `ConnectionDialog.fxml` + `ConnectionDialogController.java` — add UI input
5. Tests in `ProfileRepositoryTest.java`

**AI prompt pattern**:
```
Add a "description" text field to connection profiles. The field should be:
- Optional (nullable in DB, defaults to empty string)
- Editable in the connection dialog
- Displayed in the session list as a tooltip
- Follow existing code patterns (JDBC, FXML, JavaFX conventions)
```

### 4.2 "Add a terminal right-click context menu"

**Files to touch**:
1. `TerminalTab.java` — add ContextMenu to the terminal area
2. CSS if needed

**AI prompt pattern**:
```
Add a right-click context menu to the terminal tab with these items:
- Copy (Ctrl+Shift+C)
- Paste (Ctrl+Shift+V)
- Select All
- Clear
- Find...
Follow JavaFX ContextMenu patterns. Use existing key bindings where available.
```

### 4.3 "Add SSH port forwarding support"

**Files to touch**:
1. `SshSession.java` — add port forwarding methods
2. `ConnectionProfile.java` — add forwarding config fields
3. UI for managing forwarding rules
4. `DatabaseManager.java` — new table or columns

**AI prompt pattern**:
```
Add local port forwarding support to Raindrop. Users should be able to:
- Configure L-Tunnels (local forward) in connection settings
- Start/stop forwarding rules on an active session
- See active forwarding rules in the session info panel
Use SSHJ's Session.RemotePortForwarder API. Follow virtual thread conventions.
```

### 4.4 "Add session logging / recording"

**Files to touch**:
1. `SshSession.java` — wrap InputStream/OutputStream with a tee
2. New `SessionLogger` class
3. Config for log directory

**AI prompt pattern**:
```
Add terminal session logging. Record all SSH input/output to a file
in ~/.raindrop/logs/{session-name}_{timestamp}.log. Make it toggleable
via a setting. Use try-with-resources for file handles. The logger
should be non-blocking (buffer writes, flush periodically).
```

### 4.5 "Add a global search across connections"

**Files to touch**:
1. New `GlobalSearchController.java`
2. New FXML
3. Wire into MainController (e.g., Ctrl+K shortcut)

**AI prompt pattern**:
```
Add a global search feature triggered by Ctrl+K that searches across:
- Connection profile names and hosts
- Credential names
- SFTP recent paths
Display results in a popup list. Selecting a result navigates to that item.
Follow the existing modal dialog pattern (UTILITY stage + alwaysOnTop).
```

### 4.6 "Add a new terminal color theme"

**Files to touch**:
1. `src/main/resources/css/mytheme.css` — new stylesheet
2. `TerminalTheme.java` — register the theme name
3. `ThemeManager.java` — add theme to the available list
4. `SettingsViewController.java` — add to theme dropdown

**AI prompt pattern**:
```
Add a new terminal theme called "Dracula" with these colors:
- Background: #282a36
- Foreground: #f8f8f2
- Cursor: #f8f8f2
- ANSI colors: [list colors]
Follow the pattern in dark.css and solarized-dark.css.
Register it in ThemeManager and the settings dropdown.
```

### 4.7 "Import SSH keys from file"

**Files to touch**:
1. `KeyImporter.java` — already exists; extend if new format needed
2. `CredentialDialogController.java` — wire import button
3. `CredentialEntry.java` — if new fields needed

**AI prompt pattern**:
```
Add support for importing PuTTY (.ppk) private keys. The import flow:
- User clicks "Import Key" in the credential dialog
- File picker filters for *.ppk, *.pem, *.key
- Key is parsed and validated before saving
- Converted to OpenSSH format if needed, stored encrypted in credential table
Use the existing KeyImporter pattern. Follow virtual thread conventions.
```

## 5. Testing Patterns

### Unit Test Template

```java
package com.raindrop.mymodule;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class MyServiceTest {

    @BeforeEach
    void setUp() {
        // Clean state before each test
    }

    @Test
    void testDoSomething_withValidInput_returnsExpected() {
        // Arrange
        var input = "test-value";

        // Act
        var result = MyService.doSomething(input);

        // Assert
        assertEquals("expected", result);
    }

    @Test
    void testDoSomething_withNull_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            MyService.doSomething(null);
        });
    }
}
```

### Running Tests

```bash
# Set JDK 21
export JAVA_HOME=/path/to/jdk-21

# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.raindrop.mymodule.MyServiceTest"

# Run with coverage (if configured)
./gradlew test jacocoTestReport
```

**Important**: Tests use a throwaway SQLite database under `build/test-tmp/`. Never create test database connections manually — always go through `DatabaseManager.getConnection()`.

## 6. Code Review Checklist for AI-Generated Code

Before merging AI-generated changes, verify:

- [ ] **Virtual threads**: All I/O (file, network, DB) runs via `TaskExecutor.submit()`
- [ ] **No null returns**: Methods return `Optional<T>` or throw exceptions
- [ ] **No empty catch blocks**: Exceptions are logged or propagated
- [ ] **UI thread safety**: JavaFX property mutations wrapped in `Platform.runLater()`
- [ ] **Method length**: No method exceeds 30 lines
- [ ] **Database**: New connections via `DatabaseManager.getConnection()`, closed in try-with-resources
- [ ] **Concurrency**: No `HashMap` in shared state — use `ConcurrentHashMap`
- [ ] **SSHJ**: SFTP operations use `SshSession.getSftpClient()`, not `client.newSFTPClient()` per call
- [ ] **Tests**: New code has corresponding tests; `./gradlew test` passes
- [ ] **No secrets**: No hardcoded passwords, keys, or tokens
- [ ] **FXML encoding**: All FXML files declare `<?xml version="1.0" encoding="UTF-8"?>`

## 7. AI Tool Configuration

### Claude Code / MiMoCode

The project already includes `CLAUDE.md` which auto-loads as context. For best results:

- Reference `AGENTS.md` when asking for architectural guidance
- Let the AI use `codegraph` tools for code navigation (the project is indexed)
- Ask it to run `./gradlew test` after changes to verify correctness

### Cursor / Copilot

Add to your `.cursorrules` or project instructions:

```
Read CLAUDE.md and AGENTS.md for project conventions.
Java 21, virtual threads via TaskExecutor, no null returns,
methods under 30 lines, all I/O on virtual threads.
```

### General Tips

1. **Be specific about scope**: "Add a field to ConnectionProfile" is better than "improve the connection feature"
2. **Reference existing patterns**: "Follow the same pattern as CredentialManager" helps the AI match your codebase style
3. **Mention files to touch**: Listing affected files upfront reduces incorrect edits
4. **Ask for tests**: Always request tests alongside feature code
5. **Verify with `./gradlew test`**: Run tests after every AI-generated change

## 8. Troubleshooting

| Problem | Solution |
|---------|----------|
| AI edits `CLAUDE.md` or `AGENTS.md` | Tell it these files are reference docs, not to be modified unless explicitly asked |
| AI uses `Executors.newFixedThreadPool()` | Remind it: use `TaskExecutor.submit()` with virtual threads |
| AI returns `null` | Remind it: use `Optional<T>` or throw exceptions |
| AI creates `HashMap` in shared context | Remind it: use `ConcurrentHashMap` |
| AI opens SFTP per call | Remind it: use `session.getSftpClient()` for cached reuse |
| Tests fail after AI changes | Check if the AI touched `DatabaseManager` — tests need the override URL |
| Build fails with Java version error | Ensure `JAVA_HOME` points to JDK 21+ |
