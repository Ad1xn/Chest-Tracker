package dev.adrian.chesttracker.core.index;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.util.BlockKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The container index for a single dimension.
 *
 * <p>Three maps, each earning its place:
 * <ul>
 *   <li>{@code byPos} is the primary store.
 *   <li>{@code byChunk} makes invalidation and reconciliation per-chunk set
 *       operations rather than full scans - without it, "this chunk changed"
 *       would mean walking every record in the world.
 *   <li>{@code byItem} is an inverted index so a search costs O(matches)
 *       instead of O(containers). Searching 100k containers for one item by
 *       brute force is the difference between instant and visibly slow.
 * </ul>
 *
 * <p>Positions are {@link BlockKey}-packed longs, and identifiers are palette
 * ids, so this class holds no Minecraft types and no strings per record.
 *
 * <p><b>Not thread-safe.</b> The scanner threads hand records to the owning
 * service, which applies them on a single thread.
 */
public final class WorldIndex {

    private final Map<Long, ContainerRecord> byPos = new HashMap<>();
    private final Map<Long, Set<Long>> byChunk = new HashMap<>();
    private final Map<Integer, Set<Long>> byItem = new HashMap<>();

    private final int dimensionId;

    public WorldIndex(int dimensionId) {
        this.dimensionId = dimensionId;
    }

    public int dimensionId() {
        return dimensionId;
    }

    public int size() {
        return byPos.size();
    }

    public boolean isEmpty() {
        return byPos.isEmpty();
    }

    public ContainerRecord get(long pos) {
        return byPos.get(pos);
    }

    public boolean contains(long pos) {
        return byPos.containsKey(pos);
    }

    public Collection<ContainerRecord> all() {
        return Collections.unmodifiableCollection(byPos.values());
    }

    /**
     * Inserts or replaces the record at its position, keeping every secondary
     * index consistent. Replacing goes through {@link #remove} first so a stale
     * item no longer present in the container cannot linger in the inverted
     * index and produce a phantom search hit.
     */
    public void put(ContainerRecord record) {
        remove(record.pos());

        byPos.put(record.pos(), record);
        byChunk.computeIfAbsent(record.chunkKey(), k -> new HashSet<>()).add(record.pos());
        for (StackEntry entry : record.contents()) {
            byItem.computeIfAbsent(entry.itemId(), k -> new HashSet<>()).add(record.pos());
        }
    }

    /** Removes the record at {@code pos}, if any. Returns what was removed, or null. */
    public ContainerRecord remove(long pos) {
        ContainerRecord existing = byPos.remove(pos);
        if (existing == null) return null;

        Set<Long> chunk = byChunk.get(existing.chunkKey());
        if (chunk != null) {
            chunk.remove(pos);
            if (chunk.isEmpty()) byChunk.remove(existing.chunkKey());
        }
        for (StackEntry entry : existing.contents()) {
            Set<Long> holders = byItem.get(entry.itemId());
            if (holders != null) {
                holders.remove(pos);
                if (holders.isEmpty()) byItem.remove(entry.itemId());
            }
        }
        return existing;
    }

    /** Positions indexed within one chunk. Never null; empty when nothing is indexed there. */
    public Set<Long> positionsInChunk(long chunkKey) {
        Set<Long> positions = byChunk.get(chunkKey);
        return positions == null ? Set.of() : Collections.unmodifiableSet(positions);
    }

    public void removeChunk(long chunkKey) {
        for (Long pos : new ArrayList<>(positionsInChunk(chunkKey))) {
            remove(pos);
        }
    }

    /**
     * Drops every indexed position in a chunk that is not in {@code actual}.
     *
     * <p>This is how a container that no longer exists leaves the index. A live
     * break hook only catches removals that happen while we are watching; a
     * chest broken while the mod was disabled, the server ran without it, or
     * the world was edited externally is only ever caught here, when the chunk
     * is next seen. Callers pass the positions that genuinely still hold a
     * tracked container right now.
     *
     * @return how many stale records were dropped
     */
    public int reconcileChunk(long chunkKey, Set<Long> actual) {
        Set<Long> indexed = byChunk.get(chunkKey);
        if (indexed == null || indexed.isEmpty()) return 0;

        List<Long> stale = new ArrayList<>();
        for (Long pos : indexed) {
            if (!actual.contains(pos)) stale.add(pos);
        }
        for (Long pos : stale) remove(pos);
        return stale.size();
    }

    public void clear() {
        byPos.clear();
        byChunk.clear();
        byItem.clear();
    }

    /**
     * Runs a query and returns matches ranked nearest-first.
     *
     * <p>Candidate selection matters more than filtering here: an item filter
     * goes through the inverted index, a bounded radius walks only the chunks in
     * range, and only an unbounded query with no item filter falls back to a
     * full scan.
     */
    public List<SearchResult> query(IndexQuery query) {
        Collection<Long> candidates = selectCandidates(query);

        List<SearchResult> results = new ArrayList<>();
        double maxDistSq = query.maxDistanceSq();

        for (Long pos : candidates) {
            ContainerRecord record = byPos.get(pos);
            if (record == null) continue;
            if (!matchesFilters(record, query)) continue;

            double distSq = BlockKey.distanceSq(query.center(), record.pos());
            if (query.hasDistanceLimit() && distSq > maxDistSq) continue;

            List<StackEntry> matches = collectMatches(record, query);
            // An item filter that matched nothing in this container is not a hit,
            // even though the inverted index nominated it (nesting can be excluded).
            if (!query.itemIds().isEmpty() && matches.isEmpty()) continue;

            results.add(new SearchResult(record, distSq, matches));
        }

        results.sort((a, b) -> Double.compare(a.distanceSq(), b.distanceSq()));
        if (query.limit() > 0 && results.size() > query.limit()) {
            return List.copyOf(results.subList(0, query.limit()));
        }
        return List.copyOf(results);
    }

    private Collection<Long> selectCandidates(IndexQuery query) {
        if (!query.itemIds().isEmpty()) {
            Set<Long> union = new HashSet<>();
            for (Integer itemId : query.itemIds()) {
                Set<Long> holders = byItem.get(itemId);
                if (holders != null) union.addAll(holders);
            }
            return union;
        }

        if (query.hasDistanceLimit()) {
            return positionsWithinChunkRadius(query);
        }

        return new ArrayList<>(byPos.keySet());
    }

    /**
     * Gathers candidates from the chunks a radius could possibly touch. Cheaper
     * than a full scan whenever the radius is smaller than the indexed world,
     * and the exact per-block distance check still happens afterwards.
     */
    private Collection<Long> positionsWithinChunkRadius(IndexQuery query) {
        int centerChunkX = BlockKey.x(query.center()) >> 4;
        int centerChunkZ = BlockKey.z(query.center()) >> 4;
        int chunkRadius = (int) Math.ceil(query.maxDistance() / 16.0) + 1;

        // Falling back to a full scan is cheaper than iterating a huge chunk
        // square that covers more area than we have indexed anyway.
        long chunkSquare = (2L * chunkRadius + 1) * (2L * chunkRadius + 1);
        if (chunkSquare > byChunk.size()) {
            return new ArrayList<>(byPos.keySet());
        }

        List<Long> candidates = new ArrayList<>();
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                Set<Long> positions = byChunk.get(BlockKey.chunkKey(cx, cz));
                if (positions != null) candidates.addAll(positions);
            }
        }
        return candidates;
    }

    private boolean matchesFilters(ContainerRecord record, IndexQuery query) {
        if (!query.origins().isEmpty() && !query.origins().contains(record.origin())) return false;
        if (!query.typeIds().isEmpty() && !query.typeIds().contains(record.typeId())) return false;
        if (query.excludedTypeIds().contains(record.typeId())) return false;
        if (query.unlootedOnly() && !record.unlooted()) return false;
        if (query.knownContentsOnly() && !record.contentsKnown()) return false;
        return true;
    }

    private List<StackEntry> collectMatches(ContainerRecord record, IndexQuery query) {
        if (query.itemIds().isEmpty()) return List.of();

        List<StackEntry> matches = new ArrayList<>();
        for (StackEntry entry : record.contents()) {
            if (!query.includeNested() && entry.isNested()) continue;
            if (query.itemIds().contains(entry.itemId())) matches.add(entry);
        }
        return matches;
    }

    /**
     * One item, totalled across every container that matched.
     *
     * @param itemId         palette id of the item
     * @param totalCount     how many exist in total
     * @param containerCount how many containers hold at least one
     * @param nearestDistSq  squared distance to the closest of those containers
     */
    public record ItemSummary(int itemId, int totalCount, int containerCount, double nearestDistSq)
            implements Comparable<ItemSummary> {
        @Override
        public int compareTo(ItemSummary other) {
            // Most plentiful first, ties broken by proximity, so both "what do I
            // have a lot of" and "where is the nearest one" read naturally.
            int byCount = Integer.compare(other.totalCount, totalCount);
            return byCount != 0 ? byCount : Double.compare(nearestDistSq, other.nearestDistSq);
        }
    }

    /**
     * Totals matching containers' contents per item.
     *
     * <p>This backs the item-first view. Someone asking "where is my redstone"
     * wants one row saying they have 2,304 across four containers - not four
     * container rows to add up by hand.
     */
    public List<ItemSummary> summarise(IndexQuery query) {
        Map<Integer, int[]> totals = new HashMap<>();       // itemId -> {count, containers}
        Map<Integer, Double> nearest = new HashMap<>();

        for (SearchResult result : query(query)) {
            ContainerRecord container = result.container();
            // A container whose contents we cannot know must not inflate a total.
            if (!container.contentsKnown()) continue;

            Set<Integer> seenHere = new HashSet<>();
            for (StackEntry entry : container.contents()) {
                if (!query.includeNested() && entry.isNested()) continue;
                if (!query.itemIds().isEmpty() && !query.itemIds().contains(entry.itemId())) continue;

                int[] totalsFor = totals.computeIfAbsent(entry.itemId(), id -> new int[2]);
                totalsFor[0] += entry.count();
                // Several stacks of one item in one chest is still one container.
                if (seenHere.add(entry.itemId())) totalsFor[1]++;

                nearest.merge(entry.itemId(), result.distanceSq(), Math::min);
            }
        }

        List<ItemSummary> summaries = new ArrayList<>(totals.size());
        totals.forEach((itemId, counts) -> summaries.add(new ItemSummary(
                itemId, counts[0], counts[1], nearest.getOrDefault(itemId, Double.MAX_VALUE))));
        Collections.sort(summaries);
        return summaries;
    }

    /** Diagnostic counts for {@code /chesttracker stats}. */
    public Stats stats() {
        int known = 0;
        int unlooted = 0;
        Map<Origin, Integer> byOrigin = new HashMap<>();
        for (ContainerRecord record : byPos.values()) {
            if (record.contentsKnown()) known++;
            if (record.unlooted()) unlooted++;
            byOrigin.merge(record.origin(), 1, Integer::sum);
        }
        return new Stats(byPos.size(), known, unlooted, byChunk.size(), byItem.size(), Map.copyOf(byOrigin));
    }

    public record Stats(
            int containers,
            int withKnownContents,
            int unlooted,
            int chunks,
            int distinctItems,
            Map<Origin, Integer> byOrigin
    ) {}
}
