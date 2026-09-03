package dev.adrian.chesttracker.platform;

import net.minecraft.world.level.ChunkPos;

/**
 * Version-conditional access to chunk coordinates.
 *
 * <p>{@code ChunkPos} exposed public {@code x} and {@code z} fields up to
 * 1.21.11; 26.x made them private behind {@code x()} and {@code z()}
 * accessors. Field access cannot be papered over by an interface, so it goes
 * through here.
 */
public final class ChunkPosCompat {

    private ChunkPosCompat() {}

    public static int x(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.x();
        *///?} else {
        return pos.x;
        //?}
    }

    public static int z(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.z();
        *///?} else {
        return pos.z;
        //?}
    }
}
