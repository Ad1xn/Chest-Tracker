package dev.adrian.chesttracker.core.anvil;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NbtReaderTest {

    private static NbtCompound parse(NbtTestWriter.Compound root, Set<String> keep) throws IOException {
        byte[] bytes = NbtTestWriter.toBytes(root);
        return NbtReader.readNamedRoot(
                new java.io.DataInputStream(new ByteArrayInputStream(bytes)), keep);
    }

    @Test
    void readsEveryScalarType() throws IOException {
        NbtCompound parsed = parse(NbtTestWriter.compound()
                .put("b", (byte) 7)
                .put("s", (short) 300)
                .put("i", 70000)
                .put("l", 10_000_000_000L)
                .put("f", 1.5f)
                .put("d", 2.5d)
                .put("str", "hello"), null);

        assertEquals(7, parsed.getByte("b", (byte) 0));
        assertEquals(300, parsed.getInt("s", 0));
        assertEquals(70000, parsed.getInt("i", 0));
        assertEquals(10_000_000_000L, parsed.getLong("l", 0));
        assertEquals("hello", parsed.getString("str"));
    }

    @Test
    void readsArraysAndLists() throws IOException {
        NbtCompound parsed = parse(NbtTestWriter.compound()
                .put("ints", new int[]{1, 2, 3})
                .put("longs", new long[]{4L, 5L})
                .put("bytes", new byte[]{6, 7})
                .put("strings", List.of("a", "b")), null);

        assertArrayEquals(new int[]{1, 2, 3}, parsed.getIntArray("ints"));
        assertArrayEquals(new long[]{4L, 5L}, parsed.getLongArray("longs"));
        assertEquals(List.of("a", "b"), parsed.getList("strings"));
    }

    @Test
    void readsNestedCompoundsAndCompoundLists() throws IOException {
        NbtCompound parsed = parse(NbtTestWriter.compound()
                .put("inner", NbtTestWriter.compound().put("deep", "value"))
                .put("items", List.of(
                        NbtTestWriter.compound().put("id", "minecraft:diamond"),
                        NbtTestWriter.compound().put("id", "minecraft:emerald"))), null);

        assertEquals("value", parsed.getCompound("inner").getString("deep"));
        List<NbtCompound> items = parsed.getCompoundList("items");
        assertEquals(2, items.size());
        assertEquals("minecraft:emerald", items.get(1).getString("id"));
    }

    // --- Selective reading, the point of the whole class ---------------------

    @Test
    void keepsOnlyTheRequestedRootKeys() throws IOException {
        NbtCompound parsed = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(NbtTestWriter.compound().put("id", "minecraft:chest")))
                .put("Heightmaps", NbtTestWriter.compound().put("WORLD_SURFACE", new long[]{1L, 2L}))
                .put("sections", List.of(NbtTestWriter.compound().put("Y", (byte) 0))),
                Set.of("block_entities"));

        assertTrue(parsed.contains("block_entities"));
        assertFalse(parsed.contains("Heightmaps"), "skipped keys must not be retained");
        assertFalse(parsed.contains("sections"));
        assertEquals("minecraft:chest", parsed.getCompoundList("block_entities").get(0).getString("id"));
    }

    @Test
    void skippingStaysInSyncWithTheStream() throws IOException {
        // The value that matters comes *after* the heavy skipped payloads, so a
        // mis-sized skip would corrupt it rather than merely omit something.
        NbtCompound parsed = parse(NbtTestWriter.compound()
                .put("sections", List.of(
                        NbtTestWriter.compound().put("data", new long[512]),
                        NbtTestWriter.compound().put("data", new long[512])))
                .put("Heightmaps", NbtTestWriter.compound().put("x", new long[37]))
                .put("skippedInts", new int[1000])
                .put("skippedBytes", new byte[5000])
                .put("skippedStrings", List.of("aaa", "bbb", "ccc"))
                .put("skippedNested", NbtTestWriter.compound()
                        .put("deeper", NbtTestWriter.compound().put("v", 1)))
                .put("xPos", 42)
                .put("zPos", -17),
                Set.of("xPos", "zPos"));

        assertEquals(42, parsed.getInt("xPos", 0));
        assertEquals(-17, parsed.getInt("zPos", 0));
        assertEquals(2, parsed.size(), "nothing but the requested keys should survive");
    }

    @Test
    void skipsFixedWidthListsInOneJump() throws IOException {
        NbtCompound parsed = parse(NbtTestWriter.compound()
                .put("floats", List.of(1.0f, 2.0f, 3.0f))
                .put("doubles", List.of(1.0d, 2.0d))
                .put("marker", "end"),
                Set.of("marker"));

        assertEquals("end", parsed.getString("marker"));
    }

    // --- Robustness ---------------------------------------------------------

    @Test
    void accessorsFallBackRatherThanThrowOnWrongTypes() throws IOException {
        NbtCompound parsed = parse(NbtTestWriter.compound().put("id", 5), null);

        assertNull(parsed.getString("id"), "a wrong type must not blow up a world scan");
        assertEquals(-1, parsed.getInt("missing", -1));
        assertNull(parsed.getCompound("id"));
        assertTrue(parsed.getList("id").isEmpty());
    }

    @Test
    void rejectsGarbage() {
        byte[] garbage = {(byte) 0x42, 0x00, 0x01};
        assertThrows(IOException.class, () -> NbtReader.read(new ByteArrayInputStream(garbage)));
    }

    @Test
    void readsAnEmptyRoot() throws IOException {
        assertTrue(parse(NbtTestWriter.compound(), null).isEmpty());
    }
}
