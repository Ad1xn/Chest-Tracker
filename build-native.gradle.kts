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

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(toolchainJava)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    // Emit 21 bytecode on every target so one Mixin compatibilityLevel covers
    // all of them and the classes stay loadable everywhere. The toolchain only
    // needs to be newer so javac can read the game's own class files.
    options.release = 21
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
    "java_dep" to "21",
)

tasks.processResources {
    inputs.properties(resourceProps)
    filesMatching("fabric.mod.json") { expand(resourceProps) }
}

tasks.test { useJUnitPlatform() }
