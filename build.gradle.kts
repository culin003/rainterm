plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "1.13.1"
}

group = "com.raindrop"
version = "1.1.0"

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
    implementation("org.slf4j:slf4j-simple:2.0.18")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

    // Encryption
    implementation("org.jasypt:jasypt:1.9.3")

    // Icons — Ikonli (JavaFX bindings + FontAwesome5 pack). Font-based, cross-platform.
    implementation("org.kordamp.ikonli:ikonli-javafx:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.4.0")

    // JSON parsing for i18n
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

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
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
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

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf(
        "java.base", "java.desktop", "java.logging", "java.management",
        "java.naming", "java.net.http", "java.prefs", "java.scripting",
        "java.security.jgss", "java.sql", "java.xml", "jdk.crypto.ec",
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
