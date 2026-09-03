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
        // One shared build.gradle.kts; the few per-version differences are
        // branched inside it on the Minecraft version.
        version("1.21.11", "1.21.11")
        version("26.2", "26.2")
    }
    create(rootProject)
}

rootProject.name = "chesttracker"
