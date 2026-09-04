// Buildscript for Minecraft releases that ship unobfuscated (>= 26.1).
//
// Mojang publishes no mappings artifact for these at all, so nothing is remapped
// and mod dependencies are consumed as-is.
//
// The plugin id matters and differs from the obfuscated buildscript on purpose:
// `net.fabricmc.fabric-loom` selects the post-obfuscation pipeline, while the
// short `fabric-loom` id selects the legacy one and then fails here with
// "Configuration 'mappings' has no dependencies" - there are no mappings to
// give it. The trade-off is that this id generates no Kotlin DSL accessors for
// Loom's configurations, which is fine because this script needs none.
plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

val mcVersion = stonecutter.current.version
val toolchainJava = (property("mod.java") as String).toInt()

version = "${property("mod.version")}+$mcVersion"
group = property("mod.group")!!
base { archivesName = "${property("mod.id")}-$mcVersion" }

loom {
    // No intermediary is published for 26.1+ at all.
    noIntermediateMappings()

    splitEnvironmentSourceSets()
    mods {
        create(property("mod.id") as String) {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

repositories {
    // Loom adds the Minecraft and Fabric repositories itself; this is only for
    // Mod Menu, which is an optional compile-time dependency.
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    // Optional at runtime: the entrypoint is simply never loaded without it.
    // The integration lives in the client source set, which splitEnvironment-
    // SourceSets gives its own configurations, so it needs the client variant.
    "clientCompileOnly"("com.terraformersmc:modmenu:${property("deps.modmenu")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(toolchainJava)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    // Each target compiles at its own Minecraft version's Java level. Emitting
    // 21 everywhere was tidier, but it makes this target incapable of consuming
    // a dependency built for 25 - Gradle's variant matching rejects it outright,
    // which is what Mod Menu 20 ran into. The Mixin compatibility level is
    // templated to match, since it must be at least the bytecode we produce.
    options.release = toolchainJava
    options.encoding = "UTF-8"
}

// Captured outside the task block: inside it, property() would resolve against
// the task rather than the project.
val resourceProps = mapOf(
    "version" to project.version.toString(),
    "mod_id" to property("mod.id").toString(),
    "mod_name" to property("mod.name").toString(),
    "mc_dep" to property("mod.mc_dep").toString(),
    "loader_dep" to property("deps.fabric_loader").toString(),
    "java_dep" to toolchainJava.toString(),
    "mixin_compat" to property("mod.mixin_compat").toString(),
)

tasks.processResources {
    inputs.properties(resourceProps)
    filesMatching(listOf("fabric.mod.json", "*.mixins.json")) { expand(resourceProps) }
}

tasks.test { useJUnitPlatform() }
