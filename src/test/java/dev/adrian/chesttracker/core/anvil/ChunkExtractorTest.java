package dev.adrian.chesttracker.core.anvil;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.store.StringPalette;
import dev.adrian.chesttracker.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChunkExtractorTest {

    private final StringPalette palette = new StringPalette();

    private NbtCompound parse(NbtTestWriter.Compound root) throws IOException {
        return NbtReader.readNamedRoot(
                new DataInputStream(new ByteArrayInputStream(NbtTestWriter.toBytes(root))),
                ChunkExtractor.CHUNK_KEYS);
    }

    private static NbtTestWriter.Compound item(String id, int count) {
        return NbtTestWriter.compound().put("id", id).put("count", count);
    }

    private static NbtTestWriter.Compound blockEntity(String id, int x, int y, int z) {
        return NbtTestWriter.compound().put("id", id).put("x", x).put("y", y).put("z", z);
    }

    @Test
    void extractsAChestAndItsContents() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(blockEntity("minecraft:chest", 10, 64, -20)
                        .put("Items", List.of(item("minecraft:diamond", 5), item("minecraft:emerald", 2))))));

        ChunkExtractor.ChunkContents contents = ChunkExtractor.extract(chunk, null, 0, palette, 99L);

        assertEquals(1, contents.containers().size());
        ContainerRecord record = contents.containers().get(0);
        assertEquals(BlockKey.pack(10, 64, -20), record.pos());
        assertTrue(record.contentsKnown());
        assertEquals(2, record.contents().size());
        assertEquals(7, record.totalItems());
        assertEquals(99L, record.lastSeenTick());
        assertEquals("minecraft:chest", palette.value(record.typeId()));
    }

    @Test
    void marksUnrolledLootChestsAsNaturalAndUnlooted() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(
                        blockEntity("minecraft:chest", 0, 40, 0)
                                .put("LootTable", "minecraft:chests/simple_dungeon"),
                        blockEntity("minecraft:chest", 5, 40, 0)
                                .put("Items", List.of(item("minecraft:stone", 1))))));

        List<ContainerRecord> found = ChunkExtractor.extract(chunk, null, 0, palette, 0L).containers();

        ContainerRecord loot = found.stream().filter(ContainerRecord::unlooted).findFirst().orElseThrow();
        assertEquals(Origin.NATURAL, loot.origin());

        ContainerRecord plain = found.stream().filter(r -> !r.unlooted()).findFirst().orElseThrow();
        assertEquals(Origin.UNKNOWN, plain.origin(), "an ordinary chest is unclassifiable from the chunk alone");
    }

    @Test
    void unlootedContainersReportUnknownContentsRatherThanEmpty() throws IOException {
        // A generated chest nobody has opened has no Items on disk: its loot does
        // not exist until it is opened. Reporting "empty" would be a lie, and it
        // must contribute nothing to the item index.
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(blockEntity("minecraft:chest", 0, 40, 0)
                        .put("LootTable", "minecraft:chests/simple_dungeon"))));

        ContainerRecord record = ChunkExtractor.extract(
                chunk, Set.of("minecraft:chest"), 0, palette, 0L).containers().get(0);

        assertTrue(record.unlooted());
        assertFalse(record.contentsKnown(), "an unrolled loot chest must not claim to be empty");
        assertTrue(record.contents().isEmpty());
        assertFalse(record.isEmpty(), "isEmpty() means known-and-empty, which this is not");
    }

    @Test
    void flattensShulkerContentsWithDepth() throws IOException {
        NbtTestWriter.Compound shulker = item("minecraft:shulker_box", 1)
                .put("components", NbtTestWriter.compound()
                        .put("minecraft:container", List.of(
                                NbtTestWriter.compound().put("slot", 0).put("item", item("minecraft:diamond", 64)))));

        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(blockEntity("minecraft:barrel", 0, 64, 0)
                        .put("Items", List.of(shulker)))));

        ContainerRecord record = ChunkExtractor.extract(chunk, null, 0, palette, 0L).containers().get(0);

        assertEquals(2, record.contents().size());
        StackEntry nested = record.contents().stream().filter(StackEntry::isNested).findFirst().orElseThrow();
        assertEquals("minecraft:diamond", palette.value(nested.itemId()));
        assertEquals(1, nested.depth(), "an item inside a shulker in a barrel sits one level down");
        assertEquals(64, nested.count());
    }

    @Test
    void flattensBundleContents() throws IOException {
        NbtTestWriter.Compound bundle = item("minecraft:bundle", 1)
                .put("components", NbtTestWriter.compound()
                        .put("minecraft:bundle_contents", List.of(item("minecraft:gold_ingot", 3))));

        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(blockEntity("minecraft:chest", 0, 64, 0)
                        .put("Items", List.of(bundle)))));

        ContainerRecord record = ChunkExtractor.extract(chunk, null, 0, palette, 0L).containers().get(0);
        assertTrue(record.contents().stream()
                .anyMatch(e -> e.isNested() && "minecraft:gold_ingot".equals(palette.value(e.itemId()))));
    }

    @Test
    void honoursTheTrackedTypeFilter() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(
                        blockEntity("minecraft:chest", 0, 64, 0).put("Items", List.of(item("minecraft:stone", 1))),
                        blockEntity("minecraft:hopper", 1, 64, 0).put("Items", List.of(item("minecraft:stone", 1))))));

        List<ContainerRecord> onlyChests = ChunkExtractor.extract(
                chunk, Set.of("minecraft:chest"), 0, palette, 0L).containers();

        assertEquals(1, onlyChests.size());
        assertEquals("minecraft:chest", palette.value(onlyChests.get(0).typeId()));
    }

    @Test
    void ignoresNonContainerBlockEntities() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(
                        blockEntity("minecraft:sign", 0, 64, 0).put("front_text", "hi"),
                        blockEntity("minecraft:bed", 1, 64, 0))));

        assertTrue(ChunkExtractor.extract(chunk, null, 0, palette, 0L).containers().isEmpty());
    }

    @Test
    void extractsStructureBoundingBoxesIncludingChildren() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("structures", NbtTestWriter.compound()
                        .put("starts", NbtTestWriter.compound()
                                .put("minecraft:village_plains", NbtTestWriter.compound()
                                        .put("BB", new int[]{0, 60, 0, 40, 80, 40})
                                        .put("Children", List.of(
                                                NbtTestWriter.compound().put("BB", new int[]{5, 62, 5, 15, 70, 15})))))));

        List<ChunkExtractor.StructureBox> boxes =
                ChunkExtractor.extract(chunk, null, 0, palette, 0L).structureBoxes();

        assertEquals(2, boxes.size());
        assertTrue(boxes.get(0).contains(20, 70, 20));
        assertFalse(boxes.get(0).contains(100, 70, 20));
        assertTrue(boxes.get(1).contains(10, 65, 10), "children must be included, not just the outer box");
    }

    @Test
    void survivesMalformedBlockEntities() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(
                        NbtTestWriter.compound().put("x", 0).put("y", 64).put("z", 0),   // no id
                        blockEntity("minecraft:chest", 0, 64, 0),                         // no Items
                        NbtTestWriter.compound().put("id", "minecraft:chest")             // no position
                                .put("Items", List.of(item("minecraft:stone", 1))))));

        // One usable entry; the broken ones are skipped rather than aborting the chunk.
        // An explicit id set is what a real caller passes, and it is what lets a
        // present-but-empty chest be indexed at all.
        List<ContainerRecord> found = ChunkExtractor.extract(
                chunk, Set.of("minecraft:chest"), 0, palette, 0L).containers();
        assertEquals(1, found.size());
        assertTrue(found.get(0).contents().isEmpty());
    }

    @Test
    void readsCustomNamesFromBothPlainAndJsonForms() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(
                        blockEntity("minecraft:chest", 0, 64, 0)
                                .put("CustomName", "{\"text\":\"Ore Storage\"}")
                                .put("Items", List.of(item("minecraft:iron_ingot", 1))),
                        blockEntity("minecraft:barrel", 1, 64, 0)
                                .put("CustomName", "Food")
                                .put("Items", List.of(item("minecraft:bread", 1))))));

        List<ContainerRecord> found = ChunkExtractor.extract(chunk, null, 0, palette, 0L).containers();
        assertEquals("Ore Storage", found.get(0).customName());
        assertEquals("Food", found.get(1).customName());
    }

    @Test
    void readsLegacyCountField() throws IOException {
        NbtCompound chunk = parse(NbtTestWriter.compound()
                .put("block_entities", List.of(blockEntity("minecraft:chest", 0, 64, 0)
                        .put("Items", List.of(NbtTestWriter.compound()
                                .put("id", "minecraft:dirt").put("Count", (byte) 12))))));

        ContainerRecord record = ChunkExtractor.extract(chunk, null, 0, palette, 0L).containers().get(0);
        assertEquals(12, record.contents().get(0).count());
    }
}
