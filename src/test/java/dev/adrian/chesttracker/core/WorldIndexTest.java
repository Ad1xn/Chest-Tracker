package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.index.IndexQuery;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.util.BlockKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldIndexTest {

    private static final int DIAMOND = 1;
    private static final int EMERALD = 2;
    private static final int STONE = 3;
    private static final int CHEST = 10;
    private static final int BARREL = 11;

    private WorldIndex index;

    @BeforeEach
    void setUp() {
        index = new WorldIndex(0);
    }

    private ContainerRecord chest(int x, int y, int z, StackEntry... contents) {
        return new ContainerRecord(BlockKey.pack(x, y, z), 0, CHEST, Origin.UNKNOWN,
                null, false, true, null, 0L, List.of(contents));
    }

    @Test
    void storesAndRetrievesByPosition() {
        ContainerRecord record = chest(0, 64, 0, new StackEntry(DIAMOND, 5));
        index.put(record);

        assertEquals(1, index.size());
        assertTrue(index.contains(record.pos()));
        assertEquals(record, index.get(record.pos()));
    }

    @Test
    void removingClearsEverySecondaryIndex() {
        ContainerRecord record = chest(0, 64, 0, new StackEntry(DIAMOND, 5));
        index.put(record);
        index.remove(record.pos());

        assertEquals(0, index.size());
        assertTrue(index.positionsInChunk(record.chunkKey()).isEmpty());
        assertTrue(index.query(IndexQuery.builder().item(DIAMOND).build()).isEmpty(),
                "removed container must not remain reachable through the inverted index");
    }

    @Test
    void replacingDropsItemsThatAreNoLongerPresent() {
        ContainerRecord before = chest(0, 64, 0, new StackEntry(DIAMOND, 5));
        index.put(before);
        // Someone emptied the diamonds out and left emeralds.
        index.put(chest(0, 64, 0, new StackEntry(EMERALD, 2)));

        assertTrue(index.query(IndexQuery.builder().item(DIAMOND).build()).isEmpty(),
                "a stale item must not survive a replace and produce a phantom hit");
        assertEquals(1, index.query(IndexQuery.builder().item(EMERALD).build()).size());
        assertEquals(1, index.size());
    }

    // --- Removal of containers that no longer exist -------------------------

    @Test
    void reconcileDropsContainersThatAreNoLongerThere() {
        ContainerRecord stillThere = chest(1, 64, 1, new StackEntry(DIAMOND, 1));
        ContainerRecord broken = chest(2, 64, 2, new StackEntry(EMERALD, 1));
        index.put(stillThere);
        index.put(broken);
        long chunk = stillThere.chunkKey();
        assertEquals(chunk, broken.chunkKey(), "test fixture expects both in one chunk");

        int dropped = index.reconcileChunk(chunk, Set.of(stillThere.pos()));

        assertEquals(1, dropped);
        assertTrue(index.contains(stillThere.pos()));
        assertFalse(index.contains(broken.pos()), "a container that no longer exists must leave the index");
        assertTrue(index.query(IndexQuery.builder().item(EMERALD).build()).isEmpty(),
                "and must not still be findable by its former contents");
    }

    @Test
    void reconcileKeepsEverythingWhenNothingChanged() {
        ContainerRecord a = chest(1, 64, 1);
        ContainerRecord b = chest(2, 64, 2);
        index.put(a);
        index.put(b);

        assertEquals(0, index.reconcileChunk(a.chunkKey(), Set.of(a.pos(), b.pos())));
        assertEquals(2, index.size());
    }

    @Test
    void reconcileEmptiesAChunkWhoseContainersAreAllGone() {
        ContainerRecord a = chest(1, 64, 1);
        index.put(a);

        assertEquals(1, index.reconcileChunk(a.chunkKey(), Set.of()));
        assertEquals(0, index.size());
    }

    @Test
    void reconcileIgnoresChunksItDoesNotTrack() {
        assertEquals(0, index.reconcileChunk(BlockKey.chunkKey(99, 99), Set.of()));
    }

    @Test
    void reconcileLeavesOtherChunksAlone() {
        ContainerRecord near = chest(1, 64, 1);
        ContainerRecord far = chest(500, 64, 500);
        index.put(near);
        index.put(far);

        index.reconcileChunk(near.chunkKey(), Set.of());

        assertEquals(1, index.size());
        assertTrue(index.contains(far.pos()));
    }

    // --- Queries ------------------------------------------------------------

    @Test
    void findsContainersHoldingAnItem() {
        index.put(chest(0, 64, 0, new StackEntry(DIAMOND, 3)));
        index.put(chest(10, 64, 0, new StackEntry(STONE, 64)));

        List<SearchResult> results = index.query(IndexQuery.builder().item(DIAMOND).build());

        assertEquals(1, results.size());
        assertEquals(3, results.get(0).matchedCount());
    }

    @Test
    void ranksResultsNearestFirst() {
        index.put(chest(100, 64, 0, new StackEntry(DIAMOND, 1)));
        index.put(chest(10, 64, 0, new StackEntry(DIAMOND, 1)));
        index.put(chest(50, 64, 0, new StackEntry(DIAMOND, 1)));

        List<SearchResult> results = index.query(IndexQuery.builder()
                .item(DIAMOND).center(BlockKey.pack(0, 64, 0)).build());

        assertEquals(3, results.size());
        assertEquals(10, BlockKey.x(results.get(0).container().pos()));
        assertEquals(50, BlockKey.x(results.get(1).container().pos()));
        assertEquals(100, BlockKey.x(results.get(2).container().pos()));
    }

    @Test
    void appliesDistanceLimit() {
        index.put(chest(10, 64, 0, new StackEntry(DIAMOND, 1)));
        index.put(chest(1000, 64, 0, new StackEntry(DIAMOND, 1)));

        List<SearchResult> results = index.query(IndexQuery.builder()
                .item(DIAMOND).center(BlockKey.pack(0, 64, 0)).maxDistance(100).build());

        assertEquals(1, results.size());
        assertEquals(10, BlockKey.x(results.get(0).container().pos()));
    }

    @Test
    void distanceLimitWorksWithoutAnItemFilter() {
        // Exercises the chunk-radius candidate path rather than the inverted index.
        index.put(chest(10, 64, 0));
        index.put(chest(5000, 64, 5000));

        List<SearchResult> results = index.query(IndexQuery.builder()
                .center(BlockKey.pack(0, 64, 0)).maxDistance(64).build());

        assertEquals(1, results.size());
        assertEquals(10, BlockKey.x(results.get(0).container().pos()));
    }

    @Test
    void appliesResultLimit() {
        for (int x = 0; x < 10; x++) index.put(chest(x * 10, 64, 0, new StackEntry(DIAMOND, 1)));

        List<SearchResult> results = index.query(IndexQuery.builder()
                .item(DIAMOND).center(BlockKey.pack(0, 64, 0)).limit(3).build());

        assertEquals(3, results.size());
    }

    @Test
    void filtersByOriginAndType() {
        index.put(new ContainerRecord(BlockKey.pack(0, 64, 0), 0, CHEST, Origin.NATURAL,
                null, true, true, null, 0L, List.of(new StackEntry(DIAMOND, 1))));
        index.put(new ContainerRecord(BlockKey.pack(10, 64, 0), 0, BARREL, Origin.PLAYER_PLACED,
                null, false, true, null, 0L, List.of(new StackEntry(DIAMOND, 1))));

        assertEquals(1, index.query(IndexQuery.builder().item(DIAMOND).origin(Origin.NATURAL).build()).size());
        assertEquals(1, index.query(IndexQuery.builder().item(DIAMOND).types(Set.of(BARREL)).build()).size());
        assertEquals(1, index.query(IndexQuery.builder().item(DIAMOND).unlootedOnly(true).build()).size());
        assertEquals(2, index.query(IndexQuery.builder().item(DIAMOND).build()).size());
    }

    @Test
    void excludesNestedHitsWhenAsked() {
        // A diamond inside a shulker box inside this chest.
        index.put(chest(0, 64, 0, new StackEntry(DIAMOND, 1, 1)));

        assertEquals(1, index.query(IndexQuery.builder().item(DIAMOND).includeNested(true).build()).size());
        assertTrue(index.query(IndexQuery.builder().item(DIAMOND).includeNested(false).build()).isEmpty());
    }

    @Test
    void canDropLocationOnlyEntries() {
        index.put(ContainerRecord.locationOnly(BlockKey.pack(0, 64, 0), 0, CHEST, Origin.UNKNOWN, 0L));

        assertEquals(1, index.query(IndexQuery.builder().build()).size());
        assertTrue(index.query(IndexQuery.builder().knownContentsOnly(true).build()).isEmpty(),
                "an entry whose contents we cannot know must be excludable");
    }

    @Test
    void reportsStats() {
        index.put(chest(0, 64, 0, new StackEntry(DIAMOND, 1)));
        index.put(ContainerRecord.locationOnly(BlockKey.pack(10, 64, 0), 0, CHEST, Origin.NATURAL, 0L));

        WorldIndex.Stats stats = index.stats();
        assertEquals(2, stats.containers());
        assertEquals(1, stats.withKnownContents());
        assertEquals(1, stats.byOrigin().get(Origin.NATURAL));
    }
}
