package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.store.StringPalette;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the invariant the persistence format depends on: ids are assigned in
 * insertion order and stay stable, so a saved index can store bare ints.
 */
class StringPaletteTest {

    @Test
    void internIsStableAndSequential() {
        StringPalette palette = new StringPalette();
        assertEquals(0, palette.intern("minecraft:diamond"));
        assertEquals(1, palette.intern("minecraft:emerald"));
        assertEquals(0, palette.intern("minecraft:diamond"), "re-interning must not allocate a new id");
        assertEquals(2, palette.size());
    }

    @Test
    void lookupReportsMissingAsMinusOne() {
        StringPalette palette = new StringPalette();
        palette.intern("minecraft:stone");
        assertEquals(0, palette.lookup("minecraft:stone"));
        assertEquals(-1, palette.lookup("minecraft:dirt"));
    }

    @Test
    void valueRejectsOutOfRangeIds() {
        StringPalette palette = new StringPalette();
        palette.intern("minecraft:stone");
        assertEquals("minecraft:stone", palette.value(0));
        assertNull(palette.value(1));
        assertNull(palette.value(-1));
    }

    @Test
    void roundTripsThroughEntries() {
        StringPalette original = new StringPalette();
        List.of("a", "b", "c").forEach(original::intern);

        StringPalette restored = StringPalette.fromEntries(original.entries());

        assertEquals(original.size(), restored.size());
        for (int id = 0; id < original.size(); id++) {
            assertEquals(original.value(id), restored.value(id), "id " + id + " must survive the round trip");
        }
    }
}
