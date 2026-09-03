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

    /** Dimension ids are needed on a hot path; building the string each time is not free. */
    private static final Map<ResourceKey<Level>, String> DIMENSION_IDS = new ConcurrentHashMap<>();

    private Trackers() {}

    public static void setCurrent(TrackerService service) {
        current = service;
    }

    public static TrackerService current() {
        return current;
    }

    public static void clear() {
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
