package dev.adrian.chesttracker.core.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds real {@code .mca} bytes and reads them back. Region files come from
 * another program, sometimes mid-write, so the failure paths matter as much as
 * the happy one: a bad chunk must raise a skippable error, never wedge a scan.
 */
class RegionFileTest {

    private record Chunk(int localX, int localZ, int compression, byte[] nbt) {}

    /** Assembles a region file: header sector, timestamp sector, then chunk sectors. */
    private static Path writeRegion(Path dir, String name, List<Chunk> chunks) throws IOException {
        byte[] header = new byte[RegionFile.SECTOR_BYTES * 2];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int nextSector = 2;

        for (Chunk chunk : chunks) {
            byte[] payload = compress(chunk.compression(), chunk.nbt());

            ByteArrayOutputStream sector = new ByteArrayOutputStream();
            int declaredLength = payload.length + 1; // payload plus the compression byte
            sector.write(declaredLength >>> 24);
            sector.write(declaredLength >>> 16);
            sector.write(declaredLength >>> 8);
            sector.write(declaredLength);
            sector.write(chunk.compression());
            sector.write(payload);
            while (sector.size() % RegionFile.SECTOR_BYTES != 0) sector.write(0);

            int sectorCount = sector.size() / RegionFile.SECTOR_BYTES;
            int index = ((chunk.localX() & 31) + (chunk.localZ() & 31) * 32) * 4;
            header[index] = (byte) (nextSector >>> 16);
            header[index + 1] = (byte) (nextSector >>> 8);
            header[index + 2] = (byte) nextSector;
            header[index + 3] = (byte) sectorCount;

            body.write(sector.toByteArray());
            nextSector += sectorCount;
        }

        Path file = dir.resolve(name);
        try (var out = Files.newOutputStream(file)) {
            out.write(header);
            out.write(body.toByteArray());
        }
        return file;
    }

    private static byte[] compress(int type, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (type) {
            case 1 -> { try (var gz = new GZIPOutputStream(out)) { gz.write(data); } }
            case 2 -> { try (var df = new DeflaterOutputStream(out)) { df.write(data); } }
            case 3 -> out.write(data);
            default -> throw new IllegalArgumentException("unsupported in fixtures: " + type);
        }
        return out.toByteArray();
    }

    private static byte[] chunkNbt(int xPos, int zPos, String blockEntityId) throws IOException {
        return NbtTestWriter.toBytes(NbtTestWriter.compound()
                .put("xPos", xPos)
                .put("zPos", zPos)
                .put("Status", "minecraft:full")
                .put("sections", List.of(NbtTestWriter.compound().put("data", new long[256])))
                .put("block_entities", List.of(NbtTestWriter.compound()
                        .put("id", blockEntityId)
                        .put("x", xPos * 16 + 1)
                        .put("y", 64)
                        .put("z", zPos * 16 + 2))));
    }

    @Test
    void readsChunksBackForEveryCompressionType(@TempDir Path dir) throws IOException {
        Path file = writeRegion(dir, "r.0.0.mca", List.of(
                new Chunk(0, 0, 2, chunkNbt(0, 0, "minecraft:chest")),
                new Chunk(1, 0, 1, chunkNbt(1, 0, "minecraft:barrel")),
                new Chunk(2, 0, 3, chunkNbt(2, 0, "minecraft:hopper"))));

        try (RegionFile region = RegionFile.open(file)) {
            assertEquals(3, region.chunkCount());

            assertEquals("minecraft:chest", firstBlockEntityId(region, 0, 0));
            assertEquals("minecraft:barrel", firstBlockEntityId(region, 1, 0));
            assertEquals("minecraft:hopper", firstBlockEntityId(region, 2, 0));
        }
    }

    private static String firstBlockEntityId(RegionFile region, int x, int z) throws IOException {
        NbtCompound chunk = region.readChunk(x, z, ChunkExtractor.CHUNK_KEYS);
        return chunk.getCompoundList("block_entities").get(0).getString("id");
    }

    @Test
    void reportsAbsentChunksRatherThanFailing(@TempDir Path dir) throws IOException {
        Path file = writeRegion(dir, "r.0.0.mca", List.of(new Chunk(0, 0, 2, chunkNbt(0, 0, "minecraft:chest"))));

        try (RegionFile region = RegionFile.open(file)) {
            assertTrue(region.hasChunk(0, 0));
            assertFalse(region.hasChunk(5, 5));
            assertNull(region.readChunk(5, 5, null));
        }
    }

    @Test
    void listsPresentChunks(@TempDir Path dir) throws IOException {
        Path file = writeRegion(dir, "r.0.0.mca", List.of(
                new Chunk(0, 0, 2, chunkNbt(0, 0, "minecraft:chest")),
                new Chunk(31, 31, 2, chunkNbt(31, 31, "minecraft:chest"))));

        try (RegionFile region = RegionFile.open(file)) {
            List<int[]> present = region.presentChunks();
            assertEquals(2, present.size());
            assertArrayEquals(new int[]{0, 0}, present.get(0));
            assertArrayEquals(new int[]{31, 31}, present.get(1));
        }
    }

    @Test
    void skipsUnwantedRootKeysWhenReading(@TempDir Path dir) throws IOException {
        Path file = writeRegion(dir, "r.0.0.mca", List.of(new Chunk(0, 0, 2, chunkNbt(0, 0, "minecraft:chest"))));

        try (RegionFile region = RegionFile.open(file)) {
            NbtCompound chunk = region.readChunk(0, 0, ChunkExtractor.CHUNK_KEYS);
            assertTrue(chunk.contains("block_entities"));
            assertFalse(chunk.contains("sections"), "sections is the bulk of a chunk and must not be parsed");
        }
    }

    @Test
    void treatsAnEmptyRegionFileAsHavingNoChunks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Files.createFile(file);

        try (RegionFile region = RegionFile.open(file)) {
            assertEquals(0, region.chunkCount());
            assertFalse(region.hasChunk(0, 0));
        }
    }

    @Test
    void raisesASkippableErrorOnATruncatedChunk(@TempDir Path dir) throws IOException {
        Path file = writeRegion(dir, "r.0.0.mca", List.of(new Chunk(0, 0, 2, chunkNbt(0, 0, "minecraft:chest"))));
        // Simulate a torn read: the header still claims a chunk that is now cut off.
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, RegionFile.SECTOR_BYTES * 2 + 8));

        try (RegionFile region = RegionFile.open(file)) {
            assertTrue(region.hasChunk(0, 0));
            assertThrows(IOException.class, () -> region.readChunk(0, 0, null),
                    "a torn chunk must raise, so the scanner can skip and retry it");
        }
    }

    @Test
    void parsesRegionCoordinatesFromFilenames() {
        assertArrayEquals(new int[]{0, 0}, RegionFile.parseCoords("r.0.0.mca"));
        assertArrayEquals(new int[]{-3, 12}, RegionFile.parseCoords("r.-3.12.mca"));
        assertNull(RegionFile.parseCoords("level.dat"));
        assertNull(RegionFile.parseCoords("r.1.2.mcc"));
        assertTrue(RegionFile.isRegionFileName("r.-1.-1.mca"));
        assertFalse(RegionFile.isRegionFileName("notregion.mca"));
    }
}
