# ChestTracker

Find your stuff without opening a single chest.

ChestTracker indexes **every container in your world** within a configurable radius — including
chunks that aren't loaded — and lets you search it. It's a proactive replacement for
[Chest Tracker](https://modrinth.com/mod/chest-tracker), which only remembers containers after
you've physically opened them.

Fabric · Minecraft **1.21.11** and **26.2**

## This is a mod fully written by AI 

### Supported versions

**1.21.11 and newer.** Both current targets sit in Minecraft's unobfuscated era, so adding another
release from it (26.1, a future 26.3) is a one-line entry in `settings.gradle.kts` plus a
`versions/<mc>/gradle.properties` file.

**Below 1.21.11 is not supported and is not a small change.** Fabric Loom 1.17 reports even 1.21.8 as
a non-obfuscated environment and refuses both `officialMojangMappings()` and
`createRemapConfigurations()`, so it cannot build those versions at all. Supporting one would mean a
second Stonecutter buildscript pinned to an older Loom (~1.14) with Mojang mappings and the remap
configurations — a parallel build path to maintain, not a version bump.

## What it does

- **Indexes the whole world, not your render distance.** A background scanner reads region files
  straight off disk, so containers in unloaded chunks are found too. Radius is configurable.
- **Filters natural vs player-placed chests.** Loot chests in villages, temples and strongholds are
  classified separately from anything a player put down, and you can filter either way.
- **Searches nested storage.** A diamond inside a shulker box inside a barrel still shows up.
- **Every container type**, individually toggleable: chests, barrels, shulker boxes, ender chests,
  hoppers, droppers, dispensers, furnaces, brewing stands, crafters, and more.
- **Removed containers disappear.** Break a chest — or let a creeper do it — and it leaves the index
  immediately. Anything broken while the mod wasn't running is cleaned up on the next scan.

## Where it works

Minecraft never sends container *contents* to clients, only block positions. That single protocol
fact decides what's possible in each setup:

| Setup | Container locations | Contents | Unloaded chunks |
|---|---|---|---|
| Singleplayer / LAN host | yes | yes | yes |
| Server with ChestTracker installed | yes | yes (permission-gated) | yes |
| Vanilla server (client-side only) | loaded chunks only | only chests you've opened | no |

Singleplayer gets everything, because there the client *is* the server. On a vanilla server the mod
degrades honestly: it maps where containers are, remembers what you've seen inside, and clearly
marks entries whose contents it can't know.

## Server operators

By default **everyone on the server can search** — installing the mod is the decision that players
should be able to. Narrow it with `/chesttracker access <tier>` (takes effect immediately, no
restart) or the `permissionTier` config key:

| `permissionTier` | Who can search | What they see |
|---|---|---|
| `ALL` (default) | everyone | everything |
| `OWNED` | everyone | only containers they placed themselves (operators still see everything) |
| `OP` | operators only | everything |

Worth knowing when choosing: a full index is effectively loot x-ray — it shows where every unopened
generated chest is, and what is in other people's bases.

The tier applies to every player arriving over a connection, LAN guests included. A host playing
their own world is never gated — their screen reads the world directly.

Clients connecting to a server without the mod fall back automatically; no configuration is needed
on either side.

The server side is useful on its own — `/chesttracker find <item>` works from a vanilla client with
no mod installed.

## Building

Two Minecraft versions are built from one source tree via
[Stonecutter](https://stonecutter.kikugie.dev/).

```bash
./gradlew ":1.21.11:build"   # -> versions/1.21.11/build/libs/
./gradlew ":26.2:build"      # -> versions/26.2/build/libs/
./gradlew build              # both
```

**Gradle itself must run on JDK 25 or newer.** Loom validates the Gradle JVM, not just the compile
toolchain, so building the 26.2 target on an older JVM fails with
`Minecraft 26.2 requires Java 25 but Gradle is using <n>`. Point `JAVA_HOME` at a JDK 25+ before
building:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)   # macOS; any JDK >= 25 works
```

The per-target compile toolchain (21 for 1.21.11, 25 for 26.2) is provisioned by Gradle
automatically, and each target emits bytecode at its own level. Compiling everything at 21 was
tidier, but it leaves a target unable to consume a dependency built for 25 — Gradle's variant
matching rejects it outright.

## Licence

MIT
