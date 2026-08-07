plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "2.0.1"
}

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

group = "com.raindrop"
version = "1.3.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    // Maven Central is the primary source for everything. Listed first so it is
    // consulted before the JetBrains repo for any shared coordinates.
    mavenCentral()
    // JediTerm (com.techsenger.jeditermfx) and its JetBrains transitives live here.
    // Restrict this repo to ONLY those groups so unrelated dependencies (e.g.
    // org.bouncycastle, pulled in via sshj) are never resolved against it — that
    // avoids build failures when packages.jetbrains.team is slow/unreachable and
    // Gradle tries to list versions there for a dynamic range like [1.80,1.81).
    maven {
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        content {
            includeGroup("com.techsenger.jeditermfx")
            includeGroupByRegex("org\\.jetbrains(\\..*)?")
        }
    }
}

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml")
}

dependencies {
    // SSH/SFTP — 0.40.0 is the latest published release as of this writing.
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Encryption
    implementation("org.jasypt:jasypt:1.9.3")

    // Icons — Ikonli (JavaFX bindings + FontAwesome5 pack). Font-based, cross-platform.
    implementation("org.kordamp.ikonli:ikonli-javafx:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.4.0")

    // JSON parsing for i18n
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // JavaFX terminal emulator (Canvas-based port of JediTerm)
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.1.0") {
        // We never spawn a local PTY (SSH-only). pty4j only adds native libs and
        // is referenced through jeditermfx-app, not jeditermfx-ui.
        exclude(group = "org.jetbrains.pty4j")
    }

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Architectural rules — enforce project invariants (virtual threads, no HashMap,
    // no banned APIs). See ArchRulesTest.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

application {
    mainClass.set("com.raindrop.Launcher")
}

tasks.test {
    useJUnitPlatform {
        // Integration tests hit a real SSH server and are opt-in via the
        // `integrationTest` task or by unsetting the excludeTags filter.
        excludeTags("integration")
    }
    // Route tests to a throwaway on-disk SQLite under build/ so they never
    // touch ~/.raindrop/raindrop.db. Using a file DB (not :memory:) because
    // several call sites use try-with-resources on DatabaseManager.getConnection(),
    // which would drop an in-memory DB the moment the last connection closes.
    val testDb = layout.buildDirectory.file("test-tmp/raindrop-test.db").get().asFile
    // WAL mode (enabled by DatabaseManager) creates two sidecar files that
    // must also be cleaned up so `build/test-tmp/` is truly empty between runs.
    val sidecars = listOf(
        File(testDb.parentFile, testDb.name + "-shm"),
        File(testDb.parentFile, testDb.name + "-wal"),
        File(testDb.parentFile, testDb.name + "-journal")
    )
    doFirst {
        testDb.parentFile.mkdirs()
        testDb.delete()
        sidecars.forEach { it.delete() }
    }
    systemProperty("raindrop.db.url", "jdbc:sqlite:${testDb.absolutePath}")
    doLast {
        // Remove the test DB and its WAL/SHM sidecars after a successful run.
        testDb.delete()
        sidecars.forEach { it.delete() }
    }
}

// Opt-in task for integration tests that require a running SSH server on the
// test machine. Not wired into `check` — run manually with `./gradlew integrationTest`.
tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests that require a live SSH server."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.raindrop.Launcher"
    }
}

// JavaFX artifacts are platform-specific (the win/mac jars carry .dll/.dylib natives,
// the linux ones .so). The javafx plugin resolves only the host platform for the
// normal runtimeClasspath, so a fat jar built on Linux can't run on Windows. These
// classifier jars fill in the missing platforms; combining them with the host jars
// yields a single jar that boots on any desktop OS (JavaFX extracts the matching
// native at runtime).
// JavaFX artifacts are platform-specific (win/mac jars carry .dll/.dylib natives,
// linux ones .so). The javafx plugin resolves only the host platform for the normal
// runtimeClasspath, so a fat jar built on Linux can't run on Windows. Each platform's
// jars live in their OWN configuration: the classifier artifacts all declare the same
// capability as the unclassified module, so mixing platforms in one resolution graph
// fails. Resolving them separately and merging the resulting files avoids that.
val javafxWin by configurations.creating { isCanBeResolved = true; isCanBeConsumed = false; isTransitive = false }
val javafxMac by configurations.creating { isCanBeResolved = true; isCanBeConsumed = false; isTransitive = false }
val javafxLinux by configurations.creating { isCanBeResolved = true; isCanBeConsumed = false; isTransitive = false }

dependencies {
    listOf("base", "graphics", "controls", "fxml").forEach { mod ->
        javafxWin("org.openjfx:javafx-$mod:21:win")
        javafxMac("org.openjfx:javafx-$mod:21:mac")
        javafxLinux("org.openjfx:javafx-$mod:21:linux")
    }
}

val fatJar by tasks.registering(Jar::class) {
    description = "Builds a single cross-platform executable jar (all deps incl. JavaFX natives for Windows/macOS/Linux)."
    group = "distribution"
    archiveBaseName.set("raindrop")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Signed dependencies (bouncycastle etc.) ship META-INF/*.SF|RSA|DSA|EC whose
    // digests are invalid once classes are repacked into this jar.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    exclude("META-INF/INDEX.LIST")
    manifest {
        attributes["Main-Class"] = "com.raindrop.Launcher"
        // Mirrors the --add-opens jvmArgs used by the jpackage launcher.
        attributes["Add-Opens"] = "javafx.graphics/com.sun.javafx.tk"
        // Some deps (jackson, sqlite-jdbc) ship META-INF/versions/ entries.
        attributes["Multi-Release"] = "true"
    }
    from(sourceSets.main.get().output)
    from({
        // The javafx plugin's host-platform jars are replaced by the three explicit
        // platform configurations so every OS has its natives available.
        (configurations.runtimeClasspath.get().filter { !it.name.startsWith("javafx-") }
            + javafxWin + javafxMac + javafxLinux)
            .filter { it.extension == "jar" }
            .map { zipTree(it) }
    })
}

// sqlite-jdbc ships prebuilt JNI libraries for 24 platform/arch combinations
// (Windows, Mac, FreeBSD, Android, musl, ppc64, riscv64, ...). They account for
// 98% of that jar's 24.6MB, and OSInfo only ever loads the one matching the host.
// Repack it with just the target platform's library for the distributable.
//
// Guarded by a property so `./gradlew test` / `run` keep the untouched upstream
// jar: stripping is only correct when the artifact targets a known platform.
//
// Each CI runner builds natively for its own OS/arch (see release.yml matrix), so
// the target is inferred from the running JVM rather than hardcoded. The require()
// below guards against an unknown combination silently shipping a jar with no JNI.
fun sqliteNativeSubpath(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val platform = when {
        os.contains("win") -> "Windows"
        os.contains("mac") || os.contains("darwin") -> "Mac"
        os.contains("linux") -> "Linux"
        else -> error("Unsupported OS for sqlite-jdbc slim jar: $os")
    }
    val machine = when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        "arm" -> "arm"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported arch for sqlite-jdbc slim jar: $arch")
    }
    return "org/sqlite/native/$platform/$machine/"
}

val slimSqliteJar by tasks.registering {
    description = "Repacks sqlite-jdbc keeping only the target platform JNI library."
    group = "distribution"
    val outFile = layout.buildDirectory.file("slim-libs/sqlite-jdbc-slim.jar")
    outputs.file(outFile)
    doLast {
        val prefix = sqliteNativeSubpath()
        val original = configurations.runtimeClasspath.get()
            .single { it.name.startsWith("sqlite-jdbc-") }
        val target = outFile.get().asFile
        target.parentFile.mkdirs()
        var kept = 0
        var dropped = 0
        ZipFile(original).use { zip ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    val isNative = entry.name.startsWith("org/sqlite/native/")
                    if (isNative && !entry.name.startsWith(prefix)) {
                        dropped++
                        continue
                    }
                    if (isNative) kept++
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { input -> input.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        // A wrong prefix would silently produce a jar with no JNI library at all,
        // failing only at runtime on the user's machine.
        require(kept > 0) { "No native library matched $prefix — check the path" }
        logger.lifecycle(
            "slimSqliteJar: kept $kept, dropped $dropped natives; " +
                "${original.length() / 1048576}MB -> ${target.length() / 1048576}MB"
        )
    }
}

runtime {
    // --compress zip-9: maximum resource compression. The legacy values 0/1/2 are
    // deprecated and slated for removal; zip-2/zip-6 are equivalent but spelt the
    // new way. The whole jlink help is terse — measured ~2% on a deb artifact.
    options.set(listOf(
        "--strip-debug",
        "--compress", "zip-9",
        "--no-header-files",
        "--no-man-pages"
    ))
    // Module set derived from a scan of the app's bytecode plus jlink's transitive
    // resolution — see below for which modules are mandated by others.
    //
    // Required by app code (verified by grepping the fat jar's constant pools):
    //   java.logging (JUL used by sshj), java.naming (bouncycastle LDAP),
    //   java.security.jgss (sshj Kerberos), java.sql (sqlite-jdbc),
    //   jdk.crypto.ec (EC/ed25519 SSH keys), jdk.unsupported (sun.misc.Unsafe).
    // Required transitively by the modules above (jlink pulls them automatically,
    // listed here only for documentation):
    //   java.prefs + java.datatransfer + java.xml <- java.desktop
    //   java.scripting + java.xml                 <- javafx.fxml (FXML on module path)
    //   java.security.sasl                        <- java.security.jgss
    //   java.transaction.xa                       <- java.sql
    // java.desktop is mandatory: javafx.graphics requires it.
    //
    // java.management and java.net.http were in earlier builds but have ZERO
    // references in the fat jar; dropping them shaves ~1MB off the image.
    modules.set(listOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.naming",
        "java.prefs",
        "java.scripting",
        "java.security.jgss",
        "java.sql",
        "java.xml",
        "jdk.crypto.ec",
        "jdk.unsupported"
    ))
    jpackage {
        imageName = "Raindrop"
        installerName = "raindrop"
        appVersion = project.version.toString().replace("-SNAPSHOT", "")
        val os = System.getProperty("os.name").lowercase()
        installerType = when {
            os.contains("win") -> "msi"
            os.contains("mac") -> "dmg"
            else -> "deb"
        }
        jvmArgs = listOf(
            "--add-opens=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED"
        )
    }
}

// beryx's jpackageImage feeds the application distribution (installDist) into
// jpackage, but the resulting app-image layout differs per platform (Linux puts
// app dependencies under `<image>/lib/app`, Windows/macOS under `<image>/app`).
// Rather than patching files inside the image at a hardcoded path, swap the slim
// sqlite-jdbc in *before* jpackage copies it — in the installDist staging dir,
// which is layout-independent — so every platform's image inherits the slim jar
// regardless of its layout. `test` and `run` never touch installDist, so they
// keep the untouched upstream artifact.
afterEvaluate {
    tasks.named("installDist") {
        dependsOn(slimSqliteJar)
        doLast {
            val distLibDir = layout.buildDirectory
                .dir("install/${project.name}/lib")
                .get().asFile
            val fat = distLibDir.listFiles { f: File -> f.name.startsWith("sqlite-jdbc-") }
                ?: error("Cannot read $distLibDir")
            require(fat.isNotEmpty()) { "No sqlite-jdbc jar found in $distLibDir" }
            fat.forEach { jar ->
                val before = jar.length()
                slimSqliteJar.get().outputs.files.singleFile.copyTo(jar, overwrite = true)
                logger.lifecycle(
                    "installDist: slimmed ${jar.name} " +
                        "${before / 1048576}MB -> ${jar.length() / 1048576}MB"
                )
            }
        }
    }
}
