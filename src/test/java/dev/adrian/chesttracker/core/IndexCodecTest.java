package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.store.IndexCodec;
import dev.adrian.chesttracker.core.store.StringPalette;
import dev.adrian.chesttracker.core.util.BlockKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The index is persisted between sessions, so a codec bug loses a player's
 * whole search history silently. Every field gets a round-trip assertion.
 */
class IndexCodecTest {

    private static StringPalette palette() {
        StringPalette palette = new StringPalette();
        palette.intern("minecraft:overworld");
        palette.intern("minecraft:chest");
        palette.intern("minecraft:diamond");
        return palette;
    }

    @Test
    void roundTripsAFullyPopulatedRecord() throws IOException {
        UUID owner = UUID.randomUUID();
        ContainerRecord original = new ContainerRecord(
                BlockKey.pack(-1234, -59, 5678), 0, 1, Origin.PLAYER_PLACED, owner,
                true, true, "Ender Storage", 123456789L,
                List.of(new StackEntry(2, 64, 0, "Spare Pickaxe"), new StackEntry(2, 1, 2, null)));

        WorldIndex index = new WorldIndex(0);
        index.put(original);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexCodec.write(out, palette(), index);
        IndexCodec.Snapshot snapshot = IndexCodec.read(new ByteArrayInputStream(out.toByteArray()));

        ContainerRecord restored = snapshot.index().get(original.pos());
        assertNotNull(restored);
        assertEquals(original, restored);
        assertEquals(owner, restored.owner());
        assertEquals("Ender Storage", restored.customName());
        assertEquals("Spare Pickaxe", restored.contents().get(0).customName());
        assertEquals(2, restored.contents().get(1).depth());
    }

    @Test
    void roundTripsRecordsWithNoOwnerOrNames() throws IOException {
        ContainerRecord original = ContainerRecord.locationOnly(
                BlockKey.pack(0, 64, 0), 0, 1, Origin.UNKNOWN, 42L);

        WorldIndex index = new WorldIndex(0);
        index.put(original);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexCodec.write(out, palette(), index);
        IndexCodec.Snapshot snapshot = IndexCodec.read(new ByteArrayInputStream(out.toByteArray()));

        ContainerRecord restored = snapshot.index().get(original.pos());
        assertEquals(original, restored);
        assertNull(restored.owner());
        assertFalse(restored.contentsKnown(), "location-only must not come back claiming an empty container");
    }

    @Test
    void roundTripsThePalette() throws IOException {
        StringPalette original = palette();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexCodec.write(out, original, new WorldIndex(0));

        StringPalette restored = IndexCodec.read(new ByteArrayInputStream(out.toByteArray())).palette();

        assertEquals(original.size(), restored.size());
        for (int id = 0; id < original.size(); id++) {
            assertEquals(original.value(id), restored.value(id));
        }
    }

    @Test
    void roundTripsManyRecordsAndRebuildsTheInvertedIndex() throws IOException {
        WorldIndex index = new WorldIndex(0);
        for (int i = 0; i < 5000; i++) {
            index.put(new ContainerRecord(BlockKey.pack(i, 64, -i), 0, 1, Origin.NATURAL,
                    null, false, true, null, i, List.of(new StackEntry(2, i % 64 + 1))));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexCodec.write(out, palette(), index);
        WorldIndex restored = IndexCodec.read(new ByteArrayInputStream(out.toByteArray())).index();

        assertEquals(5000, restored.size());
        assertEquals(5000, restored.query(
                dev.adrian.chesttracker.core.index.IndexQuery.builder().item(2).build()).size(),
                "loading must rebuild the inverted index, not just the primary map");
    }

    @Test
    void varintsSurviveExtremeValues() throws IOException {
        // Zig-zag of a large positive int overflows to a negative int; if that is
        // sign-extended on the way out the value comes back wrong.
        for (int value : new int[]{0, 1, -1, 127, 128, -128, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            ContainerRecord record = new ContainerRecord(BlockKey.pack(0, 0, 0), value, value,
                    Origin.UNKNOWN, null, false, true, null, Long.MAX_VALUE,
                    List.of(new StackEntry(value, Math.abs(value % 1000), 0)));
            WorldIndex index = new WorldIndex(value);
            index.put(record);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IndexCodec.write(out, palette(), index);
            WorldIndex restored = IndexCodec.read(new ByteArrayInputStream(out.toByteArray())).index();

            ContainerRecord back = restored.get(record.pos());
            assertEquals(value, back.dimensionId(), "dimensionId " + value);
            assertEquals(value, back.typeId(), "typeId " + value);
            assertEquals(value, back.contents().get(0).itemId(), "itemId " + value);
            assertEquals(Long.MAX_VALUE, back.lastSeenTick());
        }
    }

    @Test
    void writesAndReadsThroughTheFilesystem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested").resolve("overworld.idx");
        WorldIndex index = new WorldIndex(7);
        index.put(ContainerRecord.locationOnly(BlockKey.pack(1, 2, 3), 7, 1, Origin.NATURAL, 9L));

        IndexCodec.write(file, palette(), index);

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
                "the temporary file must not be left behind");
        assertEquals(7, IndexCodec.read(file).index().dimensionId());
    }

    @Test
    void rejectsAFileThatIsNotAnIndex() {
        byte[] garbage = new byte[64];
        assertThrows(IOException.class, () -> IndexCodec.read(new ByteArrayInputStream(garbage)));
    }

    @Test
    void rejectsATruncatedFile() throws IOException {
        WorldIndex index = new WorldIndex(0);
        index.put(ContainerRecord.locationOnly(BlockKey.pack(1, 2, 3), 0, 1, Origin.NATURAL, 9L));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexCodec.write(out, palette(), index);

        byte[] truncated = new byte[out.size() / 2];
        System.arraycopy(out.toByteArray(), 0, truncated, 0, truncated.length);

        assertThrows(IOException.class, () -> IndexCodec.read(new ByteArrayInputStream(truncated)));
    }
}
