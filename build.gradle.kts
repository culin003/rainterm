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
version = "1.2.0"

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

// sqlite-jdbc ships prebuilt JNI libraries for 24 platform/arch combinations
// (Windows, Mac, FreeBSD, Android, musl, ppc64, riscv64, ...). They account for
// 98% of that jar's 24.6MB, and OSInfo only ever loads the one matching the host.
// Repack it with just the target platform's library for the distributable.
//
// Guarded by a property so `./gradlew test` / `run` keep the untouched upstream
// jar: stripping is only correct when the artifact targets a known platform.
val sqliteNativePrefix = "org/sqlite/native/Linux/x86_64/"

val slimSqliteJar by tasks.registering {
    description = "Repacks sqlite-jdbc keeping only the $sqliteNativePrefix JNI library."
    group = "distribution"
    val outFile = layout.buildDirectory.file("slim-libs/sqlite-jdbc-slim.jar")
    outputs.file(outFile)
    doLast {
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
                    if (isNative && !entry.name.startsWith(sqliteNativePrefix)) {
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
        require(kept > 0) { "No native library matched $sqliteNativePrefix — check the path" }
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
    // Derived from `java -verbose:module` against the real app: every entry below
    // was observed loading at startup. `java.prefs` is pulled in by jdk internals
    // (file chooser cache etc.) and is not visible to jdeps static analysis, so
    // it must come from runtime evidence rather than tool suggestions.
    modules.set(listOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
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

// jpackage copies the whole runtimeClasspath into the app image, so the fat
// sqlite-jdbc lands there. Swap in the slim jar afterwards — doing it here
// rather than by rewriting the classpath keeps `test` and `run` on the
// unmodified upstream artifact.
tasks.named("jpackageImage") {
    dependsOn(slimSqliteJar)
    doLast {
        val appLibDir = layout.buildDirectory
            .dir("jpackage/${project.name.replaceFirstChar { it.uppercase() }}/lib/app")
            .get().asFile
        val fat = appLibDir.listFiles { f: File -> f.name.startsWith("sqlite-jdbc-") }
            ?: error("Cannot read $appLibDir")
        require(fat.isNotEmpty()) { "No sqlite-jdbc jar found in $appLibDir" }
        fat.forEach { jar ->
            val before = jar.length()
            slimSqliteJar.get().outputs.files.singleFile.copyTo(jar, overwrite = true)
            logger.lifecycle(
                "jpackageImage: slimmed ${jar.name} " +
                    "${before / 1048576}MB -> ${jar.length() / 1048576}MB"
            )
        }
    }
}
