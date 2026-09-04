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
- 129 unit tests, green on both targets

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

Jars land in `versions/<mc>/build/libs/chest-tracker-<mc>-0.1.0+<mc>.jar`.

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
- Mod id is **`chest-tracker`**, display name "ChestTracker". **The hyphen is load-bearing.** The id
  must not be `chesttracker`: that collides with the original mod (NoRiskClient injects it from
  `meta/mod_cache/`), Fabric resolves duplicate ids by picking one, and ours silently never loaded.
  `chest-tracker` is a different string, so it does not collide, and hyphens are legal in a Fabric
  id (`^[a-z][a-z0-9-_]{1,63}$`), in a resource namespace, and in a lang key — all three of which
  the id feeds. It was `chestindex` until the rename; anything still saying `chestindex` is stale.
- **A hyphen is not legal in a Java identifier**, so mixin member prefixes are `chesttracker$`, not
  the mod id. They only have to be unique, not to match the id.
- `fabric.mod.json` still declares `"breaks": {"chesttracker": "*"}`. That predates the rename and is
  now doing something different from what it was added for: with distinct ids the two mods could
  coexist, and instead this makes Fabric refuse to launch when the original is present — which is
  exactly what NoRiskClient injecting it would do. Worth revisiting.
- Renaming the id moved three things players own: `config/chestindex.json` → `config/chest-tracker.json`
  (settings fall back to defaults), the per-world index at `data/chestindex/` → `data/chest-tracker/`
  (the world is simply re-scanned), and the payload namespace, so a renamed client and an old server
  do not recognise each other.

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
  net/      QueryDto  (wire shapes, shared by both query routes)
  util/     BlockKey (our own long packing, NOT Minecraft's)

platform/   version-conditional shims (server side), incl. NetworkCompat
net/        CustomPacketPayload types + codecs, registration, server handlers
server/     TrackerService, QueryService, Trackers (static hook target), LiveScanner,
            RegionScanner, commands
mixin/      BlockEntityMixin (setChanged), BlockMixin (setPlacedBy), LevelMixin (setBlock)
client/     ChestTrackerScreen, ConfigScreen, ClientTracker, ContainerHighlight,
            net/ServerLink, platform/Gfx + ClientCompat, ModMenuIntegration
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
| `PayloadTypeRegistry` | `playC2S()` / `playS2C()` | `serverboundPlay()` / `clientboundPlay()` | `platform/NetworkCompat` |

**Identical on both** (verified, no shim needed): `Container`, `ItemStack`, `BlockEntity`,
`BuiltInRegistries`, `DataComponents`, `LevelChunk.getBlockEntities`, `ServerChunkCache.hasChunk`/
`getChunkNow`, `MinecraftServer.getWorldPath`, `getCurrentSmoothedTickTime`, `setScreenAndShow`,
`EditBox`, `KeyMapping.Category`, `mouseClicked(MouseButtonEvent, boolean)`, `mouseDragged`,
`mouseReleased`, `blit`/`blitSprite`, `RenderPipelines.GUI_TEXTURED`, `ResourceKey.identifier()`,
`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`, `RandomizableContainerBlockEntity.getLootTable()`,
`CustomPacketPayload`(+`Type`), `StreamCodec.of`, `FriendlyByteBuf` read/write, `PermissionSet`/
`PermissionCheck`, `PlayerList.getPlayer(UUID)`, `ServerPlayNetworking` and `ClientPlayNetworking`
(`registerGlobalReceiver`/`send`/`canSend`), both `Context` types, `PacketSender.sendPacket`, and
`ServerPlayConnectionEvents`/`ClientPlayConnectionEvents`.

**Correction to an earlier note in this document:** the networking API is *not* wholly identical
across the two targets. Everything above is, but Fabric API 6 renamed the `PayloadTypeRegistry`
static accessors, so one shim is needed. `ServerPlayNetworking.createS2CPacket` and
`ClientPlayNetworking.createC2SPacket` were also renamed to `createClientboundPacket` /
`createServerboundPacket`; we do not use either.

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
- **Multiplayer**: the screen queries a server running the mod, permission-gated, and falls back
  cleanly on a vanilla server. See the Phase 7 section for what is and is not verified.
- **Config screen** via Mod Menu (optional) and `config/chest-tracker.json`.
- Keybind: `` ` `` — appears in vanilla Controls under Inventory as "Search containers".

---

## Phase 7: multiplayer networking — done

The GUI now works on a dedicated server. Both routes to the index produce the same
`core/net/QueryDto` shapes and run the same `server/QueryService`; the screen has one code path and
cannot tell which end answered it.

```
core/net/QueryDto      wire shapes: Filters, SummaryRequest/Response, ContainerRequest/Response,
                       Hello. Requests carry a correlation id and no position
server/QueryService    DTO in, DTO out. Used by BOTH the singleplayer path and the network handler
net/ChestTrackerPayloads   CustomPacketPayload types + hand-written FriendlyByteBuf codecs
net/ChestTrackerNetwork    type registration (common init, both sides) + server handlers + hello
platform/NetworkCompat     the one version shim: PayloadTypeRegistry accessors
client/net/ServerLink      connection state, correlation, timeouts, fallback
```

### Classification survives a re-read

Both scanners build a `ContainerRecord` from scratch, and neither can tell who placed a container -
that is observed once, by the `setPlacedBy` hook, and nowhere else. Until this was fixed, **putting
an item into a chest you had just placed erased the fact that you placed it**: the re-read wrote
`Origin.UNKNOWN` with a null owner straight over it. The player-placed filter therefore found
nothing, and the `OWNED` permission tier silently served nobody.

`ContainerRecord.inheriting` carries origin and owner forward, and `TrackerService.record` applies
it - one chokepoint, so the live path, the offline region scan and anything added later are all
covered. Only classification is inherited: contents, `unlooted` and `contentsKnown` come from the
new observation, so a generated chest that has since been opened still stops being flagged unlooted.

**Records written before this fix stay `UNKNOWN`** - the information is genuinely gone and cannot be
recovered by rescanning. Re-place the container, or accept that only containers placed from now on
are attributed. `/chesttracker stats` now prints the origin breakdown, which is the only way to
check attribution is working at all from in-game.

### Permission is restated on every reply

`Hello` carries `canQuery`, but it is only an opening position. **Permission can change while a
player is connected** - being opped is the obvious case - so every `SummaryResponse` and
`ContainerResponse` carries a `permitted` flag, and the client believes the most recent one.

This was a real bug: the greeting was sent once at join and cached, so opping a player did nothing
until they reconnected. Worse, the screen then refused to *ask*, because of the stale answer it was
trying to replace. The screen therefore keeps querying while refused - `mayAttempt()` is
deliberately wider than `canQuery()` - slowly, and self-corrects. Protocol version is now **2**.

### Live updates

The screen refreshes itself while open, in every environment.

`TrackerService` keeps a **per-dimension change counter**, bumped from the three methods every
mutation funnels through (`record` / `remove` / `reconcileChunk`). The screen compares that counter
rather than being called back — a change while no screen is open costs nothing, and a closed screen
cannot leave a listener behind.

- **Singleplayer** reads the counter directly. No networking, no subscription.
- **On a server** the client sends `Subscribe(true)` on open and `false` on close; the server
  compares each watcher's last-told generation against their *current dimension's* and pushes a
  bodiless `IndexChanged` at most every 10 ticks.

**The signal carries no data, on purpose.** Sending the changed rows would be a second description
of the index that can drift from the real one, and would have to re-implement the filters and the
permission tier to know what that player may see. The client re-asks through the query path it
already uses, which cannot disagree with itself.

**The trap, and why `ContainerRecord.sameDataAs` exists:** a query re-reads loaded containers, and
every re-read stamps a fresh `lastSeenTick`. Counting that as a change makes the counter climb
forever, so a watching client re-queries, which re-reads, which bumps the counter — a loop that
feeds itself. `record` therefore compares everything *except* `lastSeenTick` and only bumps on a
real difference. `TrackerServiceTest` and `ContainerRecordTest` pin this down; do not "simplify"
either into plain equality.

**Refreshing before display is now targeted.** It used to re-read every container about to be shown.
Live tracking already refreshes changed containers every tick, so all but the ones still queued are
current: `Trackers.refreshDirty` re-reads only positions still on the dirty set (usually none), and
`QueryService` re-runs its search only when that actually changed something. Note it never loads a
chunk — `refreshIfLoaded` checks `hasChunk` first, so unloaded containers are served from the saved
index.

**Decisions that are load-bearing:**

- **Registry strings on the wire, never palette ids.** The two sides' palettes are built
  independently, so the same int means a different item on each. Sending ids would appear to work
  and silently mislabel everything.
- **Requests carry no position and no dimension.** The server already knows where the asking player
  is, so sending them would add values the server must either trust or ignore — and a client that
  could move the query centre could rank a search around somewhere it has never been.
- **Correlation ids, not arrival order.** Every keystroke starts a query; on a connection with any
  jitter an early reply lands after a later one. The screen accepts only ids newer than what it is
  already showing.
- **The server announces itself; the client does not ask.** An unknown custom payload is dropped
  without a reply, so "no mod" and "not answered yet" are indistinguishable. The server sends
  `Hello` on join, and the client falls back after `serverHelloTimeoutMs` (default 3s). Because the
  client's channel list can still be in flight at join, *any* reply to a real query is also taken as
  proof — the announcement is the normal route, not the only one.
- **Permission tier `ALL` / `OWNED` / `OP`, config key `permissionTier`, default `ALL`.** It was
  `OP`, on the grounds that a full world index is loot x-ray. Changed on the owner's call: a server
  where nobody may search reads as broken rather than as safe, and installing the mod is itself the
  decision that players should be able to search. `/chesttracker access <tier>` sets it live and
  re-greets everyone, so tightening it needs no restart. The rest of the reasoning still holds: `OWNED` is enforced by an owner filter *inside* `IndexQuery`, not by
  filtering results afterwards — post-filtering would make the result limit count containers the
  player may not see. A record whose owner was never observed does not match an owner-restricted
  query; a tier that leaks on missing data is not a tier. Ops are not owner-restricted.
- **The host is never gated.** Their screen calls `QueryService` directly on the integrated server
  and never touches the network path. The tier applies to everyone arriving over a connection,
  LAN guests included.
- **Both sends are guarded by `canSend`.** Sending a peer a payload it never registered disconnects
  it — for the hello, our own greeting would have been what kicked the player.

**Verified:** both targets build; 123 unit tests green on both (up from 88), including a codec
round-trip per payload through a real `FriendlyByteBuf`, the owner-filter cases, and the change
counter's behaviour on re-reads. Both dedicated servers start clean with registration and handlers
installed, run a region scan and answer `/chesttracker stats` with no errors.

**Known cost:** while a full region scan is running, the index changes continuously, so an open
screen re-queries on its floor (400 ms). A summary query walks the whole dimension index, so on a
very large world that is real work on the server thread for as long as the screen stays open during
a scan. Not measured on a large world yet.

**Settings are now all real.** An audit found four of eight options were read by nothing:
`indexMachineContents`, `maxResults`, `highlightSeconds` and `highlightRecedingGraceSeconds`. The
highlight pair were dead because `ContainerHighlight` built one `HighlightTimer.defaults()` at
class-init and never consulted the config; `maxResults` because the screen used a hardcoded
constant; `indexMachineContents` was a second, unimplemented spelling of the machines *query*
filter, and is now `showMachines`, seeding that filter's starting position. **If you add a setting,
grep for a real read of it before believing it works.**

**The toolbar is a menu now.** Four single-letter cycling buttons (S/O/N/M) told the player neither
what they did nor what state they were in. Machines are hidden by default, so "I tipped five stacks
into a hopper and only saw what reached the chest" was undiscoverable - the hopper's contents were
indexed the whole time, just filtered out of the results. The burger menu lists each filter with its
current value.

**Screen state is remembered.** Sort, origin, the two toggles and the search text are written to
the config when the screen closes, and seeded back on open.

**Nothing in the GUI is colour-coded.** It is built out of vanilla's chest texture, so writing in
colours the rest of the game does not use made it look pasted on. Everything uses vanilla's own
label grey; the single exception is the menu, where a filter moved off its default reads at full
strength and one left alone is greyed - both vanilla greys.

**Not verified:** the actual client-to-server round trip in game. That needs a real client joining a
real server, which cannot be driven headlessly here. Worth doing before release: join a dedicated
server, confirm the grid populates, that `/chesttracker access OP` then makes a non-op see the
refusal message and that opping them clears it *without reconnecting*, and that a vanilla server
still falls back to "No index here yet." within the grace period.

## Not done

- **In-world highlight box.** Guidance is action-bar only. Needs the world-render API probed on both
  versions — the most likely place the Vulkan rework bites. `WorldRenderEvents` exists in Fabric API
  for both but the context API was not compared.
- **Vanilla-server fallback** (locations from loaded chunks + remember-on-open). Phase 7 only made
  the *detection* honest: the client now recognises a server without the mod and says "No index here
  yet." instead of appearing broken. It still indexes nothing there. **Note the README's "Where it
  works" section already claims this works** ("it maps where containers are, remembers what you've
  seen inside") — that claim is not true yet, and either the feature or the sentence needs to move
  before release.
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
