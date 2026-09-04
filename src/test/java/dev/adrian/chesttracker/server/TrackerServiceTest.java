package dev.adrian.chesttracker.server;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.util.BlockKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The change counter that drives live updates.
 *
 * <p>It has to move on every real change and on nothing else. If it under-
 * reports, an open screen silently shows stale contents; if it over-reports,
 * every watching client re-queries continuously - and because each query
 * re-reads loaded containers, that is a loop that feeds itself.
 */
class TrackerServiceTest {

    private static final String DIM = "minecraft:overworld";
    private static final long POS = BlockKey.pack(4, 64, 8);

    private TrackerService tracker;

    @BeforeEach
    void setUp(@TempDir Path storage) {
        tracker = new TrackerService(storage);
    }

    private ContainerRecord chest(long tick, StackEntry... contents) {
        return new ContainerRecord(POS, tracker.palette().intern(DIM),
                tracker.palette().intern("minecraft:chest"),
                Origin.PLAYER_PLACED, null, false, true, null, tick, List.of(contents));
    }

    @Test
    void anUnknownDimensionStartsAtZero() {
        assertEquals(0L, tracker.generation("minecraft:the_nether"));
    }

    @Test
    void recordingSomethingNewIsAChange() {
        long before = tracker.generation(DIM);
        tracker.record(DIM, chest(1, new StackEntry(1, 5)));

        assertNotEquals(before, tracker.generation(DIM));
    }

    @Test
    void rereadingTheSameContainerIsNotAChange() {
        tracker.record(DIM, chest(1, new StackEntry(1, 5)));
        long after = tracker.generation(DIM);

        // Same contents, later tick - exactly what a query-time refresh writes.
        tracker.record(DIM, chest(2, new StackEntry(1, 5)));
        tracker.record(DIM, chest(3, new StackEntry(1, 5)));

        assertEquals(after, tracker.generation(DIM),
                "a re-read must not register as a change, or watchers never stop re-querying");
    }

    @Test
    void changedContentsAreAChange() {
        tracker.record(DIM, chest(1, new StackEntry(1, 5)));
        long after = tracker.generation(DIM);

        tracker.record(DIM, chest(2, new StackEntry(1, 4)));

        assertNotEquals(after, tracker.generation(DIM));
    }

    @Test
    void removingSomethingIsAChange() {
        tracker.record(DIM, chest(1));
        long after = tracker.generation(DIM);

        tracker.remove(DIM, POS);

        assertNotEquals(after, tracker.generation(DIM));
    }

    @Test
    void removingNothingIsNotAChange() {
        tracker.record(DIM, chest(1));
        long after = tracker.generation(DIM);

        tracker.remove(DIM, BlockKey.pack(999, 64, 999));

        assertEquals(after, tracker.generation(DIM));
    }

    @Test
    void reconcilingIsAChangeOnlyWhenSomethingWasDropped() {
        tracker.record(DIM, chest(1));
        long after = tracker.generation(DIM);

        // The container is still there, so nothing is dropped.
        tracker.reconcileChunk(DIM, BlockKey.chunkOf(POS), Set.of(POS));
        assertEquals(after, tracker.generation(DIM));

        // Now it is gone.
        tracker.reconcileChunk(DIM, BlockKey.chunkOf(POS), Set.of());
        assertNotEquals(after, tracker.generation(DIM));
    }

    private ContainerRecord placedBy(java.util.UUID owner, long tick, StackEntry... contents) {
        return new ContainerRecord(POS, tracker.palette().intern(DIM),
                tracker.palette().intern("minecraft:chest"),
                Origin.PLAYER_PLACED, owner, false, true, null, tick, List.of(contents));
    }

    /** A fresh read: this is exactly what both scanners produce. */
    private ContainerRecord observed(long tick, StackEntry... contents) {
        return new ContainerRecord(POS, tracker.palette().intern(DIM),
                tracker.palette().intern("minecraft:chest"),
                Origin.UNKNOWN, null, false, true, null, tick, List.of(contents));
    }

    @Test
    void aRereadDoesNotErasePlacementOrOwner() {
        // The bug this pins down: put an item into a chest you just placed, the
        // container is re-read, and the record comes back UNKNOWN with no owner
        // - so the player-placed filter finds nothing and OWNED serves nobody.
        java.util.UUID owner = java.util.UUID.randomUUID();
        tracker.record(DIM, placedBy(owner, 1));

        tracker.record(DIM, observed(2, new StackEntry(1, 5)));

        ContainerRecord stored = tracker.index(DIM).get(POS);
        assertEquals(Origin.PLAYER_PLACED, stored.origin());
        assertEquals(owner, stored.owner());
        assertEquals(1, stored.contents().size(), "the new contents must still be taken");
    }

    @Test
    void anObservationStillEstablishesClassificationWhenNoneWasKnown() {
        tracker.record(DIM, observed(1));
        assertEquals(Origin.UNKNOWN, tracker.index(DIM).get(POS).origin());

        // A natural chest seen for the first time.
        tracker.record(DIM, new ContainerRecord(POS, tracker.palette().intern(DIM),
                tracker.palette().intern("minecraft:chest"), Origin.NATURAL, null,
                true, false, null, 2, List.of()));

        assertEquals(Origin.NATURAL, tracker.index(DIM).get(POS).origin());
    }

    @Test
    void openingAGeneratedChestStopsItBeingUnlooted() {
        // unlooted is not inherited - the new read is what knows.
        tracker.record(DIM, new ContainerRecord(POS, tracker.palette().intern(DIM),
                tracker.palette().intern("minecraft:chest"), Origin.NATURAL, null,
                true, false, null, 1, List.of()));

        tracker.record(DIM, observed(2, new StackEntry(1, 3)));

        ContainerRecord stored = tracker.index(DIM).get(POS);
        assertFalse(stored.unlooted());
        assertTrue(stored.contentsKnown());
        assertEquals(Origin.NATURAL, stored.origin(), "but where it came from is still known");
    }

    @Test
    void inheritedClassificationIsNotReportedAsAChange() {
        // Otherwise every re-read of a player-placed chest looks like a change
        // and watchers never stop re-querying.
        java.util.UUID owner = java.util.UUID.randomUUID();
        tracker.record(DIM, placedBy(owner, 1, new StackEntry(1, 5)));
        long after = tracker.generation(DIM);

        tracker.record(DIM, observed(2, new StackEntry(1, 5)));

        assertEquals(after, tracker.generation(DIM));
    }

    @Test
    void dimensionsCountSeparately() {
        // A scan running in one dimension must not wake players in another.
        tracker.record(DIM, chest(1));

        assertNotEquals(0L, tracker.generation(DIM));
        assertEquals(0L, tracker.generation("minecraft:the_nether"));
    }
}
