package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The packing is persisted to disk and sent over the wire, so a round-trip
 * failure would silently corrupt saved indexes. Negative coordinates are the
 * case that breaks naive bit packing, so they get most of the attention.
 */
class BlockKeyTest {

    @Test
    void roundTripsPositiveCoordinates() {
        long key = BlockKey.pack(100, 64, 200);
        assertEquals(100, BlockKey.x(key));
        assertEquals(64, BlockKey.y(key));
        assertEquals(200, BlockKey.z(key));
    }

    @Test
    void roundTripsNegativeCoordinates() {
        long key = BlockKey.pack(-1500, -59, -32000);
        assertEquals(-1500, BlockKey.x(key));
        assertEquals(-59, BlockKey.y(key));
        assertEquals(-32000, BlockKey.z(key));
    }

    @Test
    void roundTripsMixedSigns() {
        long key = BlockKey.pack(-7, 319, 12);
        assertEquals(-7, BlockKey.x(key));
        assertEquals(319, BlockKey.y(key));
        assertEquals(12, BlockKey.z(key));
    }

    @Test
    void roundTripsAtRepresentableLimits() {
        for (int x : new int[]{BlockKey.MIN_XZ, BlockKey.MAX_XZ, 0}) {
            for (int y : new int[]{BlockKey.MIN_Y, BlockKey.MAX_Y, 0}) {
                for (int z : new int[]{BlockKey.MIN_XZ, BlockKey.MAX_XZ, 0}) {
                    long key = BlockKey.pack(x, y, z);
                    assertEquals(x, BlockKey.x(key), "x at limits");
                    assertEquals(y, BlockKey.y(key), "y at limits");
                    assertEquals(z, BlockKey.z(key), "z at limits");
                }
            }
        }
    }

    @Test
    void coversTheWorldBorderAndBuildHeight() {
        // The vanilla world border sits at 30,000,000 and the build range is
        // -64..320; both must be comfortably representable.
        assertTrue(BlockKey.isRepresentable(30_000_000, 320, -30_000_000));
        assertTrue(BlockKey.isRepresentable(-30_000_000, -64, 30_000_000));
    }

    @Test
    void distinctPositionsProduceDistinctKeys() {
        assertNotEquals(BlockKey.pack(1, 0, 0), BlockKey.pack(0, 0, 1));
        assertNotEquals(BlockKey.pack(0, 1, 0), BlockKey.pack(0, 0, 1));
        assertNotEquals(BlockKey.pack(-1, 0, 0), BlockKey.pack(1, 0, 0));
    }

    @Test
    void derivesChunkFromBlockIncludingNegatives() {
        assertEquals(BlockKey.chunkKey(0, 0), BlockKey.chunkOf(BlockKey.pack(5, 64, 5)));
        assertEquals(BlockKey.chunkKey(1, 2), BlockKey.chunkOf(BlockKey.pack(16, 64, 33)));
        // -1 >> 4 is -1, not 0: block -1 belongs to chunk -1.
        assertEquals(BlockKey.chunkKey(-1, -1), BlockKey.chunkOf(BlockKey.pack(-1, 64, -1)));
        assertEquals(BlockKey.chunkKey(-2, -1), BlockKey.chunkOf(BlockKey.pack(-17, 64, -16)));
    }

    @Test
    void chunkKeyRoundTrips() {
        long key = BlockKey.chunkKey(-1875, 4096);
        assertEquals(-1875, BlockKey.chunkX(key));
        assertEquals(4096, BlockKey.chunkZ(key));
    }

    @Test
    void computesSquaredDistance() {
        long a = BlockKey.pack(0, 0, 0);
        long b = BlockKey.pack(3, 0, 4);
        assertEquals(25.0, BlockKey.distanceSq(a, b), 1e-9);
    }

    @Test
    void distanceDoesNotOverflowAtWorldBorderRange() {
        long a = BlockKey.pack(-30_000_000, 0, -30_000_000);
        long b = BlockKey.pack(30_000_000, 0, 30_000_000);
        double expected = 2.0 * 60_000_000.0 * 60_000_000.0;
        assertEquals(expected, BlockKey.distanceSq(a, b), expected * 1e-12);
    }
}
