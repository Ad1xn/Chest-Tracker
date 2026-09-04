package dev.adrian.chesttracker.server;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.util.BlockKey;
import dev.adrian.chesttracker.platform.ContainerTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static entry point the mixins call into.
 *
 * <p>Mixins cannot be handed dependencies, so the hooks need somewhere to find
 * the active {@link TrackerService}. There is exactly one server at a time -
 * an integrated one in singleplayer, or the dedicated one - so a single slot is
 * enough, and a null slot simply means "not running", which keeps the hooks
 * inert on a client connected to a remote server.
 */
public final class Trackers {

    private static volatile TrackerService current;
    private static volatile net.minecraft.server.MinecraftServer server;
    private static volatile dev.adrian.chesttracker.server.scan.RegionScanner regionScanner;

    /** Dimension ids are needed on a hot path; building the string each time is not free. */
    private static final Map<ResourceKey<Level>, String> DIMENSION_IDS = new ConcurrentHashMap<>();

    /**
     * Containers whose contents changed and need re-reading.
     *
     * <p>A set, so an item sorter firing twenty times a second collapses into a
     * single entry, and only a bounded number are re-read per tick.
     */
    private static final Map<String, java.util.Set<Long>> DIRTY = new ConcurrentHashMap<>();

    /** Re-reads per tick. Enough to feel instant, small enough to be invisible. */
    private static final int DIRTY_BUDGET_PER_TICK = 48;

    private Trackers() {}

    public static void setCurrent(TrackerService service, net.minecraft.server.MinecraftServer minecraftServer) {
        current = service;
        server = minecraftServer;
        regionScanner = new dev.adrian.chesttracker.server.scan.RegionScanner(service);
    }

    public static net.minecraft.server.MinecraftServer server() {
        return server;
    }

    public static dev.adrian.chesttracker.server.scan.RegionScanner regionScanner() {
        return regionScanner;
    }

    /** Whether a chunk is loaded, keyed the way the region scanner asks. */
    public static boolean isChunkLoaded(String dimensionId, long chunkKey) {
        net.minecraft.server.MinecraftServer current = server;
        if (current == null) return false;
        ServerLevel level = levelFor(dimensionId);
        return level != null && level.getChunkSource()
                .hasChunk(BlockKey.chunkX(chunkKey), BlockKey.chunkZ(chunkKey));
    }

    public static ServerLevel levelFor(String dimensionId) {
        net.minecraft.server.MinecraftServer current = server;
        if (current == null) return null;
        for (ServerLevel level : current.getAllLevels()) {
            if (dimensionId(level).equals(dimensionId)) return level;
        }
        return null;
    }

    public static TrackerService current() {
        return current;
    }

    public static void clear() {
        if (regionScanner != null) regionScanner.cancel();
        DIRTY.clear();
        regionScanner = null;
        server = null;
        current = null;
        DIMENSION_IDS.clear();
    }

    public static String dimensionId(Level level) {
        return DIMENSION_IDS.computeIfAbsent(level.dimension(), key -> key.identifier().toString());
    }

    /**
     * Called after any block change on the server.
     *
     * <p>Hooking the single choke point through which every block change flows
     * is deliberate: it catches player breaks, explosions, pistons, fire and
     * bulk world edits alike. Per-event listeners miss most of those.
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        TrackerService tracker = current;
        if (tracker == null || !(level instanceof ServerLevel)) return;
        if (!BlockKey.isRepresentable(pos.getX(), pos.getY(), pos.getZ())) return;

        long key = BlockKey.pack(pos.getX(), pos.getY(), pos.getZ());
        String dimensionId = dimensionId(level);
        if (tracker.index(dimensionId).get(key) == null) return;

        // Something we had indexed changed. If a container is still there the
        // next scan or save refreshes it; if not, it must leave the index now.
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !ContainerTypes.isContainer(blockEntity)) {
            tracker.remove(dimensionId, key);
        }
    }

    /**
     * Called whenever a block entity reports a change.
     *
     * <p>Marks it for re-reading; the work happens on the tick drain. Without
     * this the index only ever learns a container's contents when its chunk
     * unloads, so filling a chest you just placed would never show up.
     */
    public static void onContainerChanged(BlockEntity blockEntity) {
        TrackerService tracker = current;
        if (tracker == null) return;

        Level level = blockEntity.getLevel();
        // setChanged fires on the client too, where there is nothing to index.
        if (!(level instanceof ServerLevel)) return;
        if (!ContainerTypes.isContainer(blockEntity)) return;

        BlockPos pos = blockEntity.getBlockPos();
        if (!BlockKey.isRepresentable(pos.getX(), pos.getY(), pos.getZ())) return;

        DIRTY.computeIfAbsent(dimensionId(level), id -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(BlockKey.pack(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Re-reads a bounded number of changed containers.
     *
     * @return how many were refreshed this tick
     */
    public static int drainDirty() {
        TrackerService tracker = current;
        if (tracker == null || DIRTY.isEmpty()) return 0;

        int done = 0;
        for (Map.Entry<String, java.util.Set<Long>> entry : DIRTY.entrySet()) {
            ServerLevel level = levelFor(entry.getKey());
            java.util.Set<Long> positions = entry.getValue();
            if (level == null) {
                positions.clear();
                continue;
            }

            var scanner = new dev.adrian.chesttracker.server.scan.LiveScanner(tracker);
            var iterator = positions.iterator();
            while (iterator.hasNext() && done < DIRTY_BUDGET_PER_TICK) {
                long pos = iterator.next();
                iterator.remove();
                scanner.refreshIfLoaded(level, entry.getKey(), pos);
                done++;
            }
            if (done >= DIRTY_BUDGET_PER_TICK) break;
        }
        return done;
    }

    /** Indexes one currently-loaded chunk, for chunks the region scan cannot use. */
    public static void liveScanChunk(String dimensionId, long chunkKey) {
        TrackerService tracker = current;
        if (tracker == null) return;
        ServerLevel level = levelFor(dimensionId);
        if (level == null) return;

        var chunk = level.getChunkSource()
                .getChunkNow(BlockKey.chunkX(chunkKey), BlockKey.chunkZ(chunkKey));
        if (chunk == null) return;
        new dev.adrian.chesttracker.server.scan.LiveScanner(tracker)
                .scanChunk(level, chunk, dimensionId);
    }

    /** Called when a player places a block, so we can attribute ownership. */
    public static void onBlockPlaced(Level level, BlockPos pos, LivingEntity placer) {
        TrackerService tracker = current;
        if (tracker == null || !(level instanceof ServerLevel serverLevel)) return;
        if (!(placer instanceof Player player)) return;
        if (!BlockKey.isRepresentable(pos.getX(), pos.getY(), pos.getZ())) return;

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !ContainerTypes.isContainer(blockEntity)) return;

        String typeId = ContainerTypes.idOf(blockEntity);
        if (typeId == null) return;
        tracker.containerTypes().learn(typeId);

        String dimensionId = dimensionId(level);
        long key = BlockKey.pack(pos.getX(), pos.getY(), pos.getZ());
        long tick = serverLevel.getGameTime();

        ContainerRecord existing = tracker.index(dimensionId).get(key);
        if (existing != null) {
            tracker.record(dimensionId, existing.withOrigin(Origin.PLAYER_PLACED, player.getUUID()));
            return;
        }
        // A freshly placed container is empty, and we know that for a fact
        // rather than merely failing to see inside it.
        tracker.record(dimensionId, new ContainerRecord(
                key,
                tracker.palette().intern(dimensionId),
                tracker.palette().intern(typeId),
                Origin.PLAYER_PLACED,
                player.getUUID(),
                false,
                true,
                null,
                tick,
                java.util.List.of()));
    }
}
