package dev.adrian.chesttracker.core.util;

/**
 * Packs a block position into a single {@code long}.
 *
 * <p>This deliberately does <em>not</em> reuse Minecraft's own packing. The
 * index is persisted to disk and shared over the network between two different
 * Minecraft versions, so the layout has to be ours and has to be stable. The
 * platform layer converts a {@code BlockPos} into this form on the way in.
 *
 * <p>Layout: X in the top 26 bits, Z in the next 26, Y in the low 12. That
 * gives +/-33.5M on the horizontal axes (the world border sits at 30M) and
 * -2048..2047 vertically, comfortably clear of the -64..320 build range.
 */
public final class BlockKey {
    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 64 - X_BITS - Z_BITS; // 12

    private static final int Y_SHIFT = 0;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + Z_BITS;

    private static final long X_MASK = (1L << X_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    public static final int MIN_XZ = -(1 << (X_BITS - 1));
    public static final int MAX_XZ = (1 << (X_BITS - 1)) - 1;
    public static final int MIN_Y = -(1 << (Y_BITS - 1));
    public static final int MAX_Y = (1 << (Y_BITS - 1)) - 1;

    private BlockKey() {}

    public static long pack(int x, int y, int z) {
        return ((long) x & X_MASK) << X_SHIFT
             | ((long) z & Z_MASK) << Z_SHIFT
             | ((long) y & Y_MASK) << Y_SHIFT;
    }

    /** Sign-extends the stored field back to a signed int. */
    public static int x(long key) {
        return (int) (key << (64 - X_BITS - X_SHIFT) >> (64 - X_BITS));
    }

    public static int z(long key) {
        return (int) (key << (64 - Z_BITS - Z_SHIFT) >> (64 - Z_BITS));
    }

    public static int y(long key) {
        return (int) (key << (64 - Y_BITS - Y_SHIFT) >> (64 - Y_BITS));
    }

    /** True if the coordinates survive a pack/unpack round trip. */
    public static boolean isRepresentable(int x, int y, int z) {
        return x >= MIN_XZ && x <= MAX_XZ
            && z >= MIN_XZ && z <= MAX_XZ
            && y >= MIN_Y && y <= MAX_Y;
    }

    /** Chunk key (chunk x/z packed into a long) for the chunk containing this block. */
    public static long chunkOf(long key) {
        return chunkKey(x(key) >> 4, z(key) >> 4);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    public static int chunkX(long chunkKey) {
        return (int) chunkKey;
    }

    public static int chunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    /**
     * Squared distance between two packed positions, as a double so it cannot
     * overflow at world-border range where an int or long product would.
     */
    public static double distanceSq(long a, long b) {
        double dx = (double) x(a) - x(b);
        double dy = (double) y(a) - y(b);
        double dz = (double) z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }

    public static String toString(long key) {
        return x(key) + ", " + y(key) + ", " + z(key);
    }
}
