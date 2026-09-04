# ChestTracker — handoff

Context for continuing work in a fresh session. Everything below is verified, not assumed;
where something is unverified it says so explicitly.

---

## What this is

A Fabric mod that indexes **every container in a Minecraft world** — including chunks that have
never been loaded — and lets you search it from a chest-shaped GUI. It replaces
[Chest Tracker](https://modrinth.com/mod/chest-tracker), which is client-only and only remembers
containers after you physically open them.

- Repo: <https://github.com/Ad1xn/Chest-Tracker> (public, remote `origin`, branch `main`)
- Local: `/Users/adrian/chesttracker`
- Targets: **MC 1.21.11 and 26.2**, Fabric, both first-class
- Released: `v0.1.0`, with a GitHub Actions release workflow on `v*` tags
- 88 unit tests, green on both targets

### The constraint that shapes everything

A client-only mod **cannot** read container contents without opening the container. Chunk packets
carry block states (so the client always knows *where* chests are) but never inventory contents.
This is protocol-level. Hence:

| Environment | Locations | Contents | Unloaded chunks |
|---|---|---|---|
| Singleplayer / LAN | yes | yes | yes (region scan) |
| Server with the mod | yes | yes (permission-gated) | yes |
| Vanilla server | loaded chunks only | only what you opened | no |

---

## Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)   # MUST be JDK 25+
./gradlew build                                     # both targets
./gradlew ":1.21.11:build"  ./gradlew ":26.2:build"
./gradlew ":26.2:runServer" ./gradlew ":1.21.11:runClient"
```

**Gradle itself must run on JDK 25+.** Loom validates the Gradle JVM, not just the compile
toolchain; the 26.2 target fails outright otherwise. Foojay toolchain provisioning does *not* fix
this. This machine has JDK 21/22/26 but no 25 — 26 works fine.

Jars land in `versions/<mc>/build/libs/chestindex-<mc>-0.1.0+<mc>.jar`.

### Non-obvious build facts (each cost real time — do not "simplify" them away)

- **The Loom plugin id differs per target and this is deliberate.** `build-obfuscated.gradle.kts`
  (1.21.11) uses `id("fabric-loom")`; `build-native.gradle.kts` (26.2) uses
  `id("net.fabricmc.fabric-loom")`. They select *different pipelines*. The short id fails on 26.2
  with `Configuration 'mappings' has no dependencies`; the long id fails on 1.21.11.
- **Do not downgrade Loom.** 1.15.5 fails with "Cannot use Mojang mappings in a non-obfuscated
  environment" where 1.17.20 succeeds.
- **Kotlin DSL generates no accessors for Loom's configurations under Stonecutter.** Use string
  invocation: `"modImplementation"(...)`, `"modClientCompileOnly"(...)`. `minecraft(...)` and
  `mappings(...)` do work as accessors on the obfuscated script.
- 1.21.11 is genuinely obfuscated (Mojang publishes `client_mappings`); 26.2 ships no mappings.
- **Pre-1.21.11 is not supported and is not a small change.** Loom 1.17 reports even 1.21.8 as
  non-obfuscated and refuses both `officialMojangMappings()` and `createRemapConfigurations()`.
  Supporting one means a second buildscript on an older Loom.
- **Each target compiles at its own Java level** (21 / 25), and the Mixin `compatibilityLevel` is
  templated through `processResources` to match. Compiling everything at 21 was tidier but makes a
  target unable to consume a Java 25 dependency — Gradle's variant matching rejects it, which is
  what Mod Menu 20 hit.
- Mod id is **`chestindex`**, display name "ChestTracker". The id must not be `chesttracker`: it
  collides with the original mod (NoRiskClient injects it from `meta/mod_cache/`), Fabric resolves
  duplicate ids by picking one, and ours silently never loaded. `fabric.mod.json` declares
  `"breaks": {"chesttracker": "*"}`.

---

## Architecture

Single Gradle project, Stonecutter multi-version, split client/main source sets.

```
core/       PURE JAVA. No Minecraft, no Bukkit, no Fabric. Enforced by CorePurityTest,
            which walks compiled bytecode and fails on any such reference.
  model/    ContainerRecord, StackEntry, Origin
  index/    WorldIndex (byPos + byChunk + inverted byItem), IndexQuery, SearchResult
  anvil/    NbtReader/NbtCompound, RegionFile, WorldLayout, ChunkExtractor, OriginClassifier
  store/    StringPalette, IndexCodec (gzipped binary, atomic temp-file write)
  highlight/HighlightTimer  (the "moving towards it" rule, unit-tested)
  net/      QueryDto  (wire shapes — Phase 7, written, not yet wired)
  util/     BlockKey (our own long packing, NOT Minecraft's)

platform/   version-conditional shims (server side)
server/     TrackerService, Trackers (static hook target), LiveScanner, RegionScanner, commands
mixin/      BlockEntityMixin (setChanged), BlockMixin (setPlacedBy), LevelMixin (setBlock)
client/     ChestTrackerScreen, ConfigScreen, ClientTracker, ContainerHighlight,
            platform/Gfx + ClientCompat, ModMenuIntegration
```

`core` is deliberately game-free so one implementation serves both MC versions, everything is
testable without a game, and a future Paper plugin can share it verbatim.

---

## Version divergences found (all isolated in shims)

Found by `javap`-diffing the two Minecraft jars, not by guessing. **Do this rather than assume** —
guessing was wrong every single time.

| API | 1.21.11 | 26.2 | Where handled |
|---|---|---|---|
| `ItemContainerContents` | `stream()` | `allItemsCopyStream()` | `platform/ItemContentsCompat` |
| `ChunkPos` x/z | public fields | `x()` / `z()` | `platform/ChunkPosCompat` |
| GUI class | `GuiGraphics` | `GuiGraphicsExtractor` | `client/platform/Gfx` |
| Text draw | `drawString(...)` | `text(...)` | `Gfx` |
| Item draw | `renderItem(...)` | `item(...)` | `Gfx` |
| Screen render | `render(...)` | `extractRenderState(...)` | signature — conditional in each Screen |
| Screen background | `renderBackground(...)` | `extractBackground(...)` | signature — conditional |
| HUD overlay | `Gui.setOverlayMessage` | `Gui.hud.setOverlayMessage` | `ClientCompat` |
| Keybind helper | `KeyBindingHelper` | `KeyMappingHelper` | `ClientCompat` |
| Mod Menu | 17.0.0 | 20.0.1 | per-version dependency |

**Identical on both** (verified, no shim needed): `Container`, `ItemStack`, `BlockEntity`,
`BuiltInRegistries`, `DataComponents`, `LevelChunk.getBlockEntities`, `ServerChunkCache.hasChunk`/
`getChunkNow`, `MinecraftServer.getWorldPath`, `getCurrentSmoothedTickTime`, `setScreenAndShow`,
`EditBox`, `KeyMapping.Category`, `mouseClicked(MouseButtonEvent, boolean)`, `mouseDragged`,
`mouseReleased`, `blit`/`blitSprite`, `RenderPipelines.GUI_TEXTURED`, `ResourceKey.identifier()`,
`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`, `RandomizableContainerBlockEntity.getLootTable()`.

Note `ResourceLocation` is named **`Identifier`** on both.

### Stonecutter syntax trap

The inactive branch is wrapped in a block comment, so **only `//` comments may appear inside it** —
a nested `*/` closes it early. This broke `Gfx` once.

```java
//? if >=26.1 {
/*return contents.allItemsCopyStream();
*///?} else {
return contents.stream();
//?}
```

---

## What works (verified in game, both versions)

- **Offline region scan.** Reads `.mca` off disk on a background thread. Verified end to end: chest
  planted in a distant chunk, index wiped, server restarted so the chunk was unloaded, then found
  from disk with contents intact. ~1300 chunks in ~1s.
- **26.2's world layout** (`dimensions/minecraft/overworld/region`) resolves via `WorldLayout`
  probing; the sibling `poi/` dir of `.mca` files is correctly ignored.
- **Live tracking**: place/break/fill/empty, hoppers, other players. `setChanged` marks dirty; a
  bounded number re-read per tick.
- **Removal**: `Level#setBlock` hook plus reconcile-on-sight, so containers broken while the mod
  was off are cleaned up when the chunk is next seen.
- **Scan on world join** (config `scanOnWorldJoin`, default on) — observed starting by itself.
- **GUI**: vanilla `generic_54` texture, 9x6 slots, search, draggable scrollbar, 4 filter buttons
  (sort / origin / nested / machines) + close. Resource packs apply because every pixel is sampled
  from the texture.
- **Guidance**: action bar shows `item  bearing  distance`, driven by `HighlightTimer`.
- **Commands**: `/chesttracker scanworld [cancel]`, `scan`, `stats`, `find` (op-gated).
- **Config screen** via Mod Menu (optional) and `config/chestindex.json`.
- Keybind: `` ` `` — appears in vanilla Controls under Inventory as "Search containers".

---

## In progress — Phase 7: multiplayer networking

**This is the biggest remaining gap.** The GUI currently talks to the integrated server directly, so
on a dedicated server it says "No index here yet". The release notes say singleplayer-only.

Done: `core/net/QueryDto.java` — `Filters`, `SummaryRequest`/`ItemSummary`/`SummaryResponse`,
`ContainerRequest`/`ContainerHit`/`ContainerResponse`.

**Key design decision, already made and important:** the wire carries item and container ids as
**registry strings, never palette ids**. Client and server palettes are independent, so the same int
means different things on each side — sending ids would appear to work and silently mislabel
everything.

**Second decision:** singleplayer should produce the *same DTOs* rather than shortcutting to the
index. One shape means the screen has a single code path and the singleplayer route cannot rot
unnoticed. This implies refactoring `ChestTrackerScreen` off `WorldIndex.ItemSummary` /
`SearchResult` and onto the DTOs.

Remaining:
1. `server/QueryService` — takes a DTO request, returns a DTO response. Used by *both* the
   singleplayer path and the network handler.
2. Fabric `CustomPacketPayload` types + `PayloadTypeRegistry` registration. The networking API is
   **identical on both versions** (verified), so no shim needed.
3. Server handler with a permission gate. Design: config tier `ALL` / `OWNED` / `OP`, defaulting to
   `OP` on dedicated servers — a full world index is effectively loot x-ray.
4. A `hello` payload so the client knows whether the server has the mod; if none arrives within a
   grace period, fall back.
5. Rewire `ClientTracker` to use the network when not singleplayer.

---

## Not done

- **In-world highlight box.** Guidance is action-bar only. Needs the world-render API probed on both
  versions — the most likely place the Vulkan rework bites. `WorldRenderEvents` exists in Fabric API
  for both but the context API was not compared.
- **Vanilla-server fallback** (locations from loaded chunks + remember-on-open).
- **Paper plugin.** Designed in the plan, sequenced last. `core` is already game-free so it can be
  extracted as a Gradle module when that starts. Paper API: `1.21.11-R0.1-SNAPSHOT` and
  `26.2.build.121-stable`. Paper, not Spigot — Adventure matters because `/chesttracker find` is the
  whole interface for vanilla clients. Folia explicitly out of scope.
- **Item tooltips** in the GUI (hover writes into the title row instead).
- **LICENSE file** — `fabric.mod.json` claims MIT but no file exists.

---

## Testing recipes

Unit tests: `./gradlew test`. `core` needs no game.

Dedicated server with console input (used repeatedly, works well):

```bash
FIFO=/tmp/mcin; rm -f $FIFO; mkfifo $FIFO
( ./gradlew ":26.2:runServer" --no-daemon < $FIFO > /tmp/mc.log 2>&1 ) &
exec 3>$FIFO
# wait for 'Done (' in /tmp/mc.log
echo 'chesttracker stats' >&3
echo 'stop' >&3
exec 3>&-
```

`versions/<mc>/run/` holds the dev world; needs `eula.txt` with `eula=true`.

To plant a container in a distant chunk: `forceload add <x> <z>`, `setblock`, `data merge block`,
`forceload remove all`, `save-all flush`.

User's live install (jars are copied here after each build):

```
~/Library/Application Support/gg.norisk.NoRiskClientV3/profiles/21.11/mods/
~/Library/Application Support/gg.norisk.NoRiskClientV3/profiles/4ever/mods/
```

The original Chest Tracker is injected by NoRiskClient and must be disabled **in its UI** — deleting
from `mod_cache` does not stick. With `breaks` declared, the game stops at Fabric's incompatible-mods
screen until it is.

---

## Working notes

- **`javap`-diff both Minecraft jars before writing against any API.** Every assumption made without
  it was wrong. Jars:
  `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-1.21.11-loom.mappings*/...jar`
  and `.../minecraft-merged-deobf-26.2.jar`.
- Colours are **ARGB**. `0xFFFFFF` has zero alpha and draws invisibly. This bug shipped once.
- The screen's window must be drawn in the **background hook**, not the render pass — widgets render
  between them, so painting the panel in `render` hides the search field's text.
- An unlooted generated chest holds **no items on disk**; its loot does not exist until opened. It
  must be recorded `contentsKnown = false`, never as a known-empty container. This bug was made twice
  (live path, then offline path).
- `StringPalette` is not thread-safe. The region scanner uses its own palette per region and results
  are translated on arrival via `TrackerService.remap`.
