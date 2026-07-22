# Raindrop

A cross-platform SSH management desktop application built with JavaFX and Java 21 virtual threads.

## Features

- **Multi-tab SSH terminal** — manage dozens of concurrent SSH sessions with a clean tabbed interface
- **SFTP file browser** — dual-panel file manager with drag-and-drop upload/download
- **Credential vault** — encrypted storage for passwords and private keys using Jasypt
- **Connection profiles** — save, group, and quickly reconnect to your servers
- **Master password & auto-lock** — protect all stored credentials with a master password; automatic idle lock
- **Key import** — import SSH private keys from files with automatic format detection
- **Terminal emulation** — powered by JediTerm (xterm-compatible, 24-bit color)
- **Multiple themes** — Dark, Light, Green-on-Black, Solarized Dark
- **Internationalization** — English, Simplified Chinese, Traditional Chinese (easily extensible)
- **Cross-platform** — Windows, macOS, Linux (native installers via jpackage)

## Screenshots

> Screenshots coming soon. Contributions welcome!

## Requirements

- **Java 21** or later (JDK required for building; JRE for running)
- **Gradle 8.5+** (wrapper included)
- Linux users: `libgtk-3`, `libgl1`, and JavaFX native libraries

## Building from Source

```bash
# Clone the repository
git clone https://github.com/cooper/raindrop.git
cd raindrop

# Build
./gradlew build

# Run tests
./gradlew test

# Run the application
./gradlew run

# Create native installer (optional)
./gradlew jpackage
```

## Project Structure

```
src/main/java/com/raindrop/
├── Launcher.java           Entry point
├── RaindropApp.java        Application initialization
├── core/                   SSH sessions, connection management, SFTP, key loading
│   ├── SshSession.java
│   ├── ConnectionManager.java
│   ├── TaskExecutor.java
│   ├── SftpService.java
│   └── KeyLoader.java
├── terminal/               Terminal emulation (JediTerm-based)
│   ├── RaindropJediTermFxWidget.java
│   ├── RaindropTerminalPanel.java
│   ├── SshTtyConnector.java
│   ├── RaindropSettingsProvider.java
│   └── TerminalTheme.java
├── credential/             Encrypted credential management
│   ├── CredentialManager.java
│   ├── CredentialEntry.java
│   └── KeyImporter.java
├── storage/                SQLite persistence
│   ├── DatabaseManager.java
│   ├── ProfileRepository.java
│   └── ConnectionProfile.java
├── security/               Master password, idle lock, migration
│   ├── SecurityManager.java
│   ├── PasswordKdf.java
│   ├── IdleWatchdog.java
│   └── MigrationRunner.java
├── ui/                     JavaFX controllers
│   ├── MainController.java
│   ├── TabManager.java
│   ├── ConnectionDialogController.java
│   ├── CredentialDialogController.java
│   ├── SftpBrowserController.java
│   ├── SettingsViewController.java
│   ├── SessionListPaneController.java
│   ├── QuickConnectBarController.java
│   └── security/           Lock/setup/reset UI
│       ├── LockController.java
│       ├── MasterPasswordSetupController.java
│       └── DestructiveResetController.java
└── util/                   Helpers
    ├── CryptoUtil.java
    ├── ConfigManager.java
    ├── ThemeManager.java
    └── I18nManager.java

src/main/resources/
├── fxml/           JavaFX layout files
├── css/            Theme stylesheets (dark, light, green-on-black, solarized-dark)
└── i18n/           Translation JSON files (en_US, zh_CN, zh_TW)
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 LTS (virtual threads) |
| GUI | JavaFX 21 + FXML |
| SSH/SFTP | [SSHJ](https://github.com/hierynomus/sshj) 0.40.0 |
| Terminal | [JediTermFX](https://github.com/techsenger/jeditermfx) 1.1.0 |
| Database | SQLite (embedded, zero-config) |
| Encryption | Jasypt 1.9.3 (PBEWithHMACSHA256AndAES_256, master-password derived) |
| JSON | Jackson 2.16.1 (i18n message loading) |
| Icons | Ikonli (FontAwesome 5) |
| Build | Gradle 8.5 (Kotlin DSL) |
| Installer | jpackage |

## Configuration

Raindrop stores all data in `~/.raindrop/`:

| File | Purpose |
|------|---------|
| `raindrop.db` | SQLite database — connection profiles, credentials, application settings (theme, font size, window size, master password verifier, etc.) |

All configuration is managed through `ConfigManager` which reads/writes to the `app_setting` table in SQLite. No separate config files are used.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on reporting issues, submitting pull requests, and development setup.

## License

This project is licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 Raindrop Contributors
