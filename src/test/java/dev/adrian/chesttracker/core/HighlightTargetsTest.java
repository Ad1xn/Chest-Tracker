package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.highlight.HighlightTargets;
import dev.adrian.chesttracker.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HighlightTargetsTest {

    private static long at(int x, int y, int z) {
        return BlockKey.pack(x, y, z);
    }

    @Test
    void picksTheClosestOfSeveral() {
        List<Long> targets = List.of(at(100, 64, 0), at(10, 64, 0), at(50, 64, 0));
        assertEquals(at(10, 64, 0), HighlightTargets.nearest(targets, 0, 64, 0));
    }

    @Test
    void handsGuidanceOverAsThePlayerMoves() {
        long near = at(0, 64, 0);
        long far = at(200, 64, 0);
        List<Long> targets = List.of(near, far);

        // This is the whole reason the choice is remade every tick rather than
        // fixed when the key was pressed: walking past one target towards
        // another has to retarget, or the player is marched away from the one
        // they are standing next to.
        assertEquals(near, HighlightTargets.nearest(targets, 5, 64, 0), "standing by the near one");
        assertEquals(far, HighlightTargets.nearest(targets, 150, 64, 0), "walked most of the way");
    }

    @Test
    void countsHeightNotJustGroundDistance() {
        // A chest 66 blocks straight up is nearer than one 80 blocks along the
        // flat, and picking it proves height is in the distance at all. Getting
        // this wrong in the obvious direction - ignoring y - would make a
        // storage room directly overhead read as being at zero distance.
        List<Long> overhead = List.of(at(0, 130, 0), at(80, 64, 0));
        assertEquals(at(0, 130, 0), HighlightTargets.nearest(overhead, 0, 64, 0));

        // And the same pair the other way round: 136 up loses to 80 along.
        List<Long> tooHigh = List.of(at(0, 200, 0), at(80, 64, 0));
        assertEquals(at(80, 64, 0), HighlightTargets.nearest(tooHigh, 0, 64, 0));
    }

    @Test
    void aTieKeepsTheEarlierTarget() {
        long first = at(-10, 64, 0);
        long second = at(10, 64, 0);
        // Equidistant. Picking strictly-closer means the answer is stable
        // rather than flickering between the two while the player stands still.
        assertEquals(first, HighlightTargets.nearest(List.of(first, second), 0, 64, 0));
        assertEquals(first, HighlightTargets.nearest(List.of(first, second), 0, 64, 0));
    }

    @Test
    void aSingleTargetIsItsOwnNearest() {
        assertEquals(at(7, 8, 9), HighlightTargets.nearest(List.of(at(7, 8, 9)), 0, 0, 0));
    }

    @Test
    void emptyAndNullSelectionsDoNotThrow() {
        assertEquals(0L, HighlightTargets.nearest(List.of(), 0, 64, 0));
        assertEquals(0L, HighlightTargets.nearest(null, 0, 64, 0));
    }

    @Test
    void worksWithNegativeCoordinates() {
        List<Long> targets = List.of(at(-980, 63, -930), at(-12, 64, -8));
        assertEquals(at(-980, 63, -930), HighlightTargets.nearest(targets, -1000, 64, -940));
        assertEquals(at(-12, 64, -8), HighlightTargets.nearest(targets, 0, 64, 0));
    }
}
