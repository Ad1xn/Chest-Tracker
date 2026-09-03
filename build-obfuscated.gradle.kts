// Buildscript for obfuscated Minecraft releases (<= 1.21.11).
//
// 1.21.11 genuinely is obfuscated - Mojang publishes client_mappings for it -
// so it needs Mojang mappings and remapped mod dependencies. 26.x ships no
// mappings artifact at all and must NOT declare any, which is why the two
// versions cannot share one buildscript.
//
// Two things here are load-bearing and easy to break:
//
//  * The plugin id must be `fabric-loom`, not `net.fabricmc.fabric-loom`.
//    Only the short id gets Kotlin DSL type-safe accessors generated under
//    Stonecutter; with the long id, `mappings(...)` and `modImplementation(...)`
//    fail to compile with "Unresolved reference".
//  * Do not downgrade Loom to "support" the obfuscated era. Older Loom (1.15.5)
//    fails here with "Cannot use Mojang mappings in a non-obfuscated
//    environment"; 1.17.20 handles 1.21.11 correctly.
plugins {
    id("fabric-loom") version "1.17.20"
}

val mcVersion = stonecutter.current.version
val toolchainJava = (property("mod.java") as String).toInt()

version = "${property("mod.version")}+$mcVersion"
group = property("mod.group")!!
base { archivesName = "${property("mod.id")}-$mcVersion" }

loom {
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
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

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
