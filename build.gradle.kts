plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "1.13.1"
}

group = "com.raindrop"
version = "1.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
}

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml")
}

dependencies {
    // SSH/SFTP
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")

    // Encryption
    implementation("org.jasypt:jasypt:1.9.3")

    // Icons — Ikonli (JavaFX bindings + FontAwesome5 pack). Font-based, cross-platform.
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.3.1")

    // JSON parsing for i18n
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")

    // JavaFX terminal emulator (Canvas-based port of JediTerm)
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.1.0") {
        // We never spawn a local PTY (SSH-only). pty4j only adds native libs and
        // is referenced through jeditermfx-app, not jeditermfx-ui.
        exclude(group = "org.jetbrains.pty4j")
    }

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.raindrop.Launcher")
}

tasks.test {
    useJUnitPlatform()
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
