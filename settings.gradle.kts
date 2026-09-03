pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    kotlinController = true
    shared {
        // Separate buildscripts because the two eras need different Loom
        // versions, which a plugins{} block cannot select conditionally.
        version("1.21.11", "1.21.11").buildscript("build-obfuscated.gradle.kts")
        version("26.2", "26.2").buildscript("build-native.gradle.kts")
    }
    create(rootProject)
}

rootProject.name = "chesttracker"
