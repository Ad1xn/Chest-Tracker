// Shared buildscript for every Minecraft target.
//
// Both 1.21.11 and 26.2 ship *unobfuscated*: Loom refuses both
// `officialMojangMappings()` ("Cannot use Mojang mappings in a non-obfuscated
// environment") and `createRemapConfigurations` ("Cannot create remap
// configurations in a non-obfuscated environment"). So neither target declares
// mappings and mod dependencies are consumed with plain `implementation`.
//
// The only per-version differences are the two flags in the loom block below.
plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

val mcVersion = stonecutter.current.version
val toolchainJava = (property("mod.java") as String).toInt()
val hasIntermediary = stonecutter.eval(mcVersion, "<26.1")

version = "${property("mod.version")}+$mcVersion"
group = property("mod.group")!!
base { archivesName = "${property("mod.id")}-$mcVersion" }

loom {
    if (hasIntermediary) {
        // 1.21.11 was the last release to get Fabric intermediary mappings, and
        // Fabric API there still ships transitive access wideners in the
        // intermediary namespace. Loom cannot remap them on a non-obfuscated
        // game and dies with "Expected official namespace for access widener
        // entry, found: intermediary". We do not depend on Fabric API's
        // transitive wideners, so skip processing them; if we ever need a
        // widened member, declare it in our own accesswidener instead.
        enableTransitiveAccessWideners = false
    } else {
        // No intermediary is published for 26.1+ at all.
        noIntermediateMappings()
    }

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
    // both and the classes stay loadable everywhere. The toolchain only needs
    // to be newer so javac can read the game's own class files.
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
