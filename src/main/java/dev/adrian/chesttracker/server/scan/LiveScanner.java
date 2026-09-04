package dev.adrian.chesttracker.server.scan;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.util.BlockKey;
import dev.adrian.chesttracker.platform.ChunkPosCompat;
import dev.adrian.chesttracker.platform.ContainerTypes;
import dev.adrian.chesttracker.platform.LiveContainerReader;
import dev.adrian.chesttracker.server.TrackerService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indexes containers from chunks that are currently loaded.
 *
 * <p>Cheap and always current, but limited to what the server already has in
 * memory. Reaching the rest of the world is the offline region scanner's job;
 * the two produce identical records, so the index cannot tell them apart.
 *
 * <p>Every scanned chunk is also reconciled, so a scan does not just add
 * containers - it removes ones that are no longer there.
 */
public final class LiveScanner {

    private final TrackerService tracker;

    public LiveScanner(TrackerService tracker) {
        this.tracker = tracker;
    }

    /** What a scan did, for command feedback and progress reporting. */
    public record ScanResult(int chunksScanned, int chunksSkipped, int containersFound, int staleRemoved) {}

    /**
     * Scans loaded chunks in a square of {@code chunkRadius} around a centre.
     *
     * <p>Chunks that are not loaded are counted as skipped rather than forced
     * into memory: loading chunks to index them would generate terrain and
     * stall the server, which is exactly what the offline scanner avoids.
     */
    public ScanResult scanAround(ServerLevel level, BlockPos center, int chunkRadius) {
        String dimensionId = level.dimension().identifier().toString();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        int scanned = 0;
        int skipped = 0;
        int found = 0;
        int removed = 0;

        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    skipped++;
                    continue;
                }
                ChunkScan result = scanChunk(level, chunk, dimensionId);
                scanned++;
                found += result.found();
                removed += result.removed();
            }
        }
        return new ScanResult(scanned, skipped, found, removed);
    }

    /** Public because the chunk-unload hook calls scanChunk directly. */
    public record ChunkScan(int found, int removed) {}

    /** Indexes one loaded chunk and reconciles it against what is really there. */
    public ChunkScan scanChunk(ServerLevel level, LevelChunk chunk, String dimensionId) {
        long tick = level.getGameTime();
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();

        Set<Long> actual = new HashSet<>();
        int found = 0;

        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            if (!ContainerTypes.isContainer(blockEntity)) continue;

            BlockPos pos = entry.getKey();
            if (!BlockKey.isRepresentable(pos.getX(), pos.getY(), pos.getZ())) continue;

            String typeId = ContainerTypes.idOf(blockEntity);
            if (typeId == null) continue;
            // Teach the offline scanner about modded containers we meet live.
            tracker.containerTypes().learn(typeId);

            long key = BlockKey.pack(pos.getX(), pos.getY(), pos.getZ());
            actual.add(key);
            tracker.record(dimensionId, toRecord(blockEntity, key, typeId, dimensionId, tick,
                    naturalityOf(level, pos, key, dimensionId)));
            found++;
        }

        long chunkKey = BlockKey.chunkKey(ChunkPosCompat.x(chunk.getPos()), ChunkPosCompat.z(chunk.getPos()));
        int removed = tracker.reconcileChunk(dimensionId, chunkKey, actual);
        return new ChunkScan(found, removed);
    }

    /**
     * Re-reads one container if its chunk is loaded, so a result is never shown
     * with stale contents.
     *
     * <p>This is what makes staleness invisible where it matters. Contents in an
     * unloaded chunk cannot have changed since we last saw them, and contents in
     * a loaded chunk are re-read here, for the handful of containers actually
     * about to be displayed.
     *
     * @return true if a container is still there, false if it is gone (in which
     *         case it has also been dropped from the index)
     */
    public boolean refreshIfLoaded(ServerLevel level, String dimensionId, long pos) {
        int x = BlockKey.x(pos);
        int y = BlockKey.y(pos);
        int z = BlockKey.z(pos);
        if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return true; // Unloaded: frozen, so what we have is still true.
        }

        BlockPos blockPos = new BlockPos(x, y, z);
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null || !ContainerTypes.isContainer(blockEntity)) {
            tracker.remove(dimensionId, pos);
            return false;
        }

        String typeId = ContainerTypes.idOf(blockEntity);
        if (typeId == null) return true;
        tracker.record(dimensionId, toRecord(blockEntity, pos, typeId, dimensionId, level.getGameTime(),
                naturalityOf(level, blockPos, pos, dimensionId)));
        return true;
    }

    /**
     * Whether a container we are seeing for the first time is part of generated
     * structure.
     *
     * <p>Without this the live path classified nothing: a village or temple
     * chest that somebody had already looted came back {@code UNKNOWN}, because
     * the only signal being used was an unrolled loot table. The offline region
     * scan has classified against structure bounds all along, so the two
     * disagreed about the same chest depending on which one saw it.
     *
     * <p>Only asked for containers not already indexed. The answer cannot
     * change for a position, the lookup is not free, and re-reads happen
     * constantly - so paying for it once is the whole budget.
     */
    private Origin naturalityOf(ServerLevel level, BlockPos blockPos, long pos, String dimensionId) {
        if (tracker.index(dimensionId).get(pos) != null) return Origin.UNKNOWN;

        // A piece test rather than the structure's bounding box: a village's box
        // covers a lot of ground that is not the village, and a chest a player
        // built next door is not a village chest.
        StructureStart start = level.structureManager()
                .getStructureWithPieceAt(blockPos, holder -> true);
        return start != null && start.isValid() ? Origin.NATURAL : Origin.UNKNOWN;
    }

    private ContainerRecord toRecord(BlockEntity blockEntity, long pos, String typeId,
                                     String dimensionId, long tick, Origin observed) {
        // A generated chest that nobody has opened still has its loot table
        // unrolled, and reports an EMPTY inventory - its items do not exist
        // until someone opens it. Recording that as "empty" would be a lie, so
        // it is stored as contents-unknown and flagged unlooted instead.
        boolean unlooted = blockEntity instanceof RandomizableContainerBlockEntity randomizable
                && randomizable.getLootTable() != null;

        int dimId = tracker.palette().intern(dimensionId);
        int typeKey = tracker.palette().intern(typeId);

        if (unlooted) {
            // An unrolled loot table is proof on its own, whatever the structure
            // lookup said.
            return new ContainerRecord(pos, dimId, typeKey, Origin.NATURAL, null,
                    true, false, null, tick, List.of());
        }

        List<StackEntry> contents = LiveContainerReader.read((Container) blockEntity, tracker.palette());
        // UNKNOWN here never overwrites an established classification -
        // TrackerService#record merges it with whatever is already known.
        return new ContainerRecord(pos, dimId, typeKey, observed, null,
                false, true, null, tick, contents);
    }
}
