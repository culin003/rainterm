package com.raindrop.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import net.schmizz.sshj.SSHClient;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.concurrent.Executors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Architectural rules that enforce the project invariants documented in
 * {@code AGENTS.md} and {@code CLAUDE.md}.
 *
 * <p>These tests run as part of the normal {@code test} suite (no
 * {@code @Tag("integration")}) so they are always checked in CI.
 */
@AnalyzeClasses(packages = "com.raindrop", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchRulesTest {

    // ── Rule 1: Virtual threads only via TaskExecutor ────────────────────
    // No production code should create its own thread pools or raw threads.

    @ArchTest
    static final ArchRule noExecutorServiceCreators =
            noClasses().should().callMethod(Executors.class, "newFixedThreadPool", int.class)
                    .andShould().callMethod(Executors.class, "newSingleThreadExecutor")
                    .andShould().callMethod(Executors.class, "newCachedThreadPool")
                    .andShould().callMethod(Executors.class, "newWorkStealingPool")
                    .andShould().callMethod(Executors.class, "newScheduledThreadPool", int.class)
                    .as("All I/O must run on virtual threads via TaskExecutor, "
                            + "not custom thread pools. See AGENTS.md §1.");

    @ArchTest
    static final ArchRule noRawThreads =
            noClasses().should().callConstructor(Thread.class)
                    .as("Use TaskExecutor.submit() instead of new Thread(). "
                            + "See AGENTS.md §1.");

    // ── Rule 2: No HashMap field types ───────────────────────────────────
    // Shared mutable state must use ConcurrentHashMap. Local variables are
    // exempt (they are thread-private).

    @ArchTest
    static final ArchRule noHashMapFieldTypes =
            noFields().should().haveRawType(HashMap.class)
                    .as("Fields must not be typed as HashMap. "
                            + "Use ConcurrentHashMap for shared maps, or "
                            + "Local variable HashMap is acceptable. "
                            + "See AGENTS.md §9.");

    // ── Rule 3: No Files.readAllLines / Files.readAllBytes ───────────────
    // These APIs load the entire file into memory, which is unsafe for
    // potentially large files. Use BufferedReader.readLine() instead.

    @ArchTest
    static final ArchRule noFilesReadAllLines =
            noClasses().should().callMethod(Files.class, "readAllLines", java.nio.file.Path.class)
                    .andShould().callMethod(Files.class, "readAllLines", java.nio.file.Path.class, java.nio.charset.Charset.class)
                    .andShould().callMethod(Files.class, "readAllBytes", java.nio.file.Path.class)
                    .as("Use BufferedReader.readLine() for potentially large files. "
                            + "Files.readAllLines/readAllBytes loads the entire file into memory. "
                            + "See AGENTS.md §10.");

    // ── Rule 4: newSFTPClient only inside SshSession ─────────────────────
    // SFTP clients must be obtained via SshSession.getSftpClient() which
    // caches and reuses the instance. Creating a new SFTP client per
    // operation adds unnecessary SSH handshake overhead.

    @ArchTest
    static final ArchRule noNewSftpClientOutsideSshSession =
            noClasses().that().resideOutsideOfPackage("..core..")
                    .should().callMethod(SSHClient.class, "newSFTPClient")
                    .as("SFTP clients must be obtained via SshSession.getSftpClient(). "
                            + "Do not call SSHClient.newSFTPClient() directly. "
                            + "See AGENTS.md §8.");

    // ── Rule 5: No raw file streams for large files ──────────────────────
    // Files.readString is acceptable for small known-size files (e.g., SSH
    // private keys), but always prefer BufferedReader for anything
    // potentially large. Method-length (≤30 lines) is enforced separately by
    // Checkstyle (see config/checkstyle/checkstyle.xml), since ArchUnit
    // cannot measure source line counts.
}