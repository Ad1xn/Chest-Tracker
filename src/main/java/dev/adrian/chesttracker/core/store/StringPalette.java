package dev.adrian.chesttracker.core.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional String&lt;-&gt;int mapping used to deduplicate item and container
 * type identifiers across the index and its on-disk form.
 *
 * <p>A world can hold hundreds of thousands of stacks drawn from a few thousand
 * distinct item ids, so storing an int per stack instead of a string is the
 * difference between an index that fits in memory and one that does not.
 *
 * <p>Ids are assigned in insertion order and never reused, so a persisted index
 * can store raw ids and rebuild the mapping by replaying the palette in order.
 *
 * <p>Not thread-safe; callers hold the index lock.
 */
public final class StringPalette {
    private final Map<String, Integer> toId = new HashMap<>();
    private final List<String> byId = new ArrayList<>();

    /** Returns the existing id for {@code value}, assigning a new one if absent. */
    public int intern(String value) {
        Integer existing = toId.get(value);
        if (existing != null) return existing;
        int id = byId.size();
        byId.add(value);
        toId.put(value, id);
        return id;
    }

    /** Returns the id for {@code value}, or -1 if it has never been interned. */
    public int lookup(String value) {
        return toId.getOrDefault(value, -1);
    }

    /** Returns the value for {@code id}, or null if out of range. */
    public String value(int id) {
        return (id < 0 || id >= byId.size()) ? null : byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /** Palette contents in id order, for serialisation. */
    public List<String> entries() {
        return List.copyOf(byId);
    }

    /** Rebuilds a palette from {@link #entries()} output. */
    public static StringPalette fromEntries(List<String> entries) {
        StringPalette palette = new StringPalette();
        for (String entry : entries) palette.intern(entry);
        return palette;
    }
}
