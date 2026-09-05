# ChestTracker - This mod was fully written using Claude Code!

Find your stuff without opening a single chest.

ChestTracker indexes **every container in your world** within a configurable radius — including
chunks that aren't loaded — and lets you search it. It's a proactive replacement for
[Chest Tracker](https://modrinth.com/mod/chest-tracker), which only remembers containers after
you've physically opened them.

Fabric · Minecraft **1.21.11** and **26.2**

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
- **Only reads what changed.** Each region file is remembered with its size and timestamp, so the
  second scan of a world reads almost nothing — and a scan interrupted by quitting resumes where it
  stopped instead of starting over.
- **Filters natural vs built chests.** Loot chests in villages, temples and strongholds are
  classified separately from anything a player put there, and you can filter either way.
- **Searches nested storage.** A diamond inside a shulker box inside a barrel still shows up, and
  the item panel tells you how many of your total are sealed inside something.
- **Every container type**, individually toggleable: chests, barrels, shulker boxes, ender chests,
  hoppers, droppers, dispensers, furnaces, brewing stands, crafters, and more.
- **Removed containers disappear.** Break a chest — or let a creeper do it — and it leaves the index
  immediately. Anything broken while the mod wasn't running is cleaned up on the next scan.

## Using it

**Two keys**, both under a **ChestTracker** heading in Controls:

| Default | What it does |
|---|---|
| `` ` `` | Open the search screen |
| `Z` | Search for whatever your cursor is over, without opening anything |

> On a non-QWERTY keyboard the second one is wherever `Z` sits on a US layout — the key labelled
> **Y** on QWERTZ, for instance. That is how Minecraft handles every keybind; rebind it if you'd
> rather press the key with `Z` printed on it.

**In the search screen**, items are shown in a chest-shaped grid, most plentiful first.

- **Left-click** an item to outline every container holding it and close the screen.
- **Right-click** an item for the list of places, nearest first, with distances.
- **Hold shift** over an item for the detail panel: totals, how many containers, how many are sealed
  inside shulker boxes, and how far the nearest one is.
- **Buttons along the bottom** switch dimension — only for dimensions that actually hold something,
  plus your **ender chest** when it isn't empty.
- A **bar under the search field** appears while the world is still being read.

**In any container window**, a small magnifier sits at the top right. Left-click opens the search
screen; **right-drag** moves it and remembers where you put it.

**Once you find something**, ChestTracker marks it in the world: a box on each container, a trail of
marks rising from it so it's findable across a base, and the nearest one picked out in a second
colour. Containers past render distance are drawn at the horizon rather than not at all. When you
arrive and open the chest, the slot holding your item pulses — and if it's inside a shulker box, the
shulker pulses instead, then the item once you open that.

Everything above is configurable through Mod Menu, including the marker colours.

## Where it works

Minecraft never sends container *contents* to clients, only block positions. That single protocol
fact decides what's possible in each setup:

| Setup | Container locations | Contents | Unloaded chunks |
|---|---|---|---|
| Singleplayer / LAN host | yes | yes | yes |
| Server with ChestTracker installed | yes | yes (permission-gated) | yes |
| Vanilla server (no mod on the server) | nothing yet | nothing yet | no |

Singleplayer gets everything, because there the client *is* the server.

**On a vanilla server the mod currently indexes nothing.** It detects that the server doesn't have
it and says so — "No index here yet." — rather than appearing broken, but that is all it does there
today. Mapping container locations from loaded chunks, and remembering what you've seen inside,
would be a client-side index that does not exist yet.

**Client and server must run the same version.** The two speak a versioned protocol and refuse each
other when it doesn't match, rather than silently misreading one another.

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

### Commands

| Command | What it does |
|---|---|
| `/chesttracker scanworld` | Reads region files that changed since the last scan |
| `/chesttracker scanworld override` | Throws the index away and reads the whole world again |
| `/chesttracker scanworld cancel` | Stops a running scan; what it read is kept |
| `/chesttracker stats` | Container counts, origins, scan progress |
| `/chesttracker find <item>` | Where an item is, in chat |
| `/chesttracker access [tier]` | Show or set who may search |

Use `override` when the index looks *wrong* rather than merely incomplete: an ordinary scan corrects
and adds, but never removes something it doesn't encounter. Searches are empty until it finishes.

The index lives in the world folder, at `<world>/data/chest-tracker/`, beside a plain-text record of
which region files have been read. Deleting that record makes the next scan read everything again;
deleting the folder starts from nothing.

## Installing

Drop the jar for your Minecraft version in `mods/`. Fabric API is required; Mod Menu is optional and
only adds the settings screen.

The mod id is **`chest-tracker`**, deliberately *not* the original mod's `chesttracker` — the two
would collide, and Fabric resolves duplicate ids by silently loading one of them. If you are
upgrading from a build that called itself `chestindex`, delete that jar: the ids differ, so both
would load at once.

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

Tagging a commit `v*` builds both targets and publishes them to GitHub Releases.

## Licence

MIT
