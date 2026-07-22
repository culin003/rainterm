# Contributing to Raindrop

Thank you for considering contributing to Raindrop! This document covers how to get started.

## Development Setup

### Prerequisites

- **JDK 21** (or later) — [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/) recommended
- **Gradle 8.5+** (wrapper `./gradlew` is included)
- **Git**

### Getting Started

```bash
# Fork and clone
git clone https://github.com/<your-username>/raindrop.git
cd raindrop

# Build and run tests
./gradlew build

# Run the app
./gradlew run
```

## Code Conventions

### Java

- **Java 21 features**: use virtual threads, records, sealed classes, and pattern matching where appropriate
- **No fixed thread pools** — all I/O runs on virtual threads via `TaskExecutor`
- **No `null` returns** — use `Optional` instead
- **No empty catch blocks** — handle or log exceptions
- **Methods under 30 lines** — extract helper methods for clarity
- **No `Thread.sleep()`** — use `CountDownLatch` or `CompletableFuture`
- **UI updates** must go through `Platform.runLater()`

### Naming

| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase | `SshSession`, `TerminalBuffer` |
| Methods | camelCase | `connect()`, `processInput()` |
| Constants | UPPER_SNAKE_CASE | `MAX_BUFFER_SIZE` |
| Packages | lowercase | `com.raindrop.core` |

### Testing

- Unit tests go in `src/test/java/com/raindrop/`
- Use JUnit 5 (`@Test`, `@BeforeEach`, `@AfterEach`)
- Test names: `test{Method}{Scenario}` (e.g., `testEncryptDecryptRoundTrip`)
- All tests must pass before submitting a PR
- Tests use a throwaway SQLite database — never touch `~/.raindrop/raindrop.db`

```bash
./gradlew test
```

## Submitting Changes

1. Create a feature branch: `git checkout -b feature/my-feature`
2. Make your changes following the conventions above
3. Write or update tests as needed
4. Ensure all tests pass: `./gradlew test`
5. Commit with a clear message describing **what** and **why**
6. Push and open a pull request against `main`

### Commit Messages

Use concise, descriptive messages:

```
Add SFTP directory listing caching

Cache SFTP ls() results for 5 seconds to reduce round-trips
when browsing large directories.
```

## Reporting Issues

Open a GitHub issue with:

- **Bug reports**: steps to reproduce, expected vs actual behavior, OS/JDK version
- **Feature requests**: describe the use case and your proposed solution
- **Questions**: describe what you're trying to accomplish

## Security

If you discover a security vulnerability, please **do not** open a public issue. Instead, email the maintainers directly or use GitHub's private vulnerability reporting.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
