package dev.adrian.chesttracker.core.anvil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed NBT compound.
 *
 * <p>Values are stored as plain Java objects rather than a tag class hierarchy:
 * {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code Float},
 * {@code Double}, {@code byte[]}, {@code int[]}, {@code long[]},
 * {@code String}, {@code List<Object>} and {@code NbtCompound}. We only ever
 * read NBT, and the accessors below are the entire surface we need, so a tag
 * hierarchy would be ceremony without payoff.
 *
 * <p>Accessors are forgiving by design. This parses files written by a
 * different program, sometimes a different Minecraft version, occasionally
 * mid-write. A wrong or missing type yields the supplied default rather than an
 * exception, so one odd chunk cannot abort a world scan.
 */
public final class NbtCompound {

    private final Map<String, Object> values;

    public NbtCompound() {
        this.values = new LinkedHashMap<>();
    }

    public NbtCompound(Map<String, Object> values) {
        this.values = values;
    }

    void put(String key, Object value) {
        values.put(key, value);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public Object raw(String key) {
        return values.get(key);
    }

    public String getString(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String s ? s : fallback;
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public int getInt(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    public long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Number n ? n.longValue() : fallback;
    }

    public byte getByte(String key, byte fallback) {
        Object value = values.get(key);
        return value instanceof Number n ? n.byteValue() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Number n ? n.byteValue() != 0 : fallback;
    }

    public NbtCompound getCompound(String key) {
        Object value = values.get(key);
        return value instanceof NbtCompound c ? c : null;
    }

    public int[] getIntArray(String key) {
        Object value = values.get(key);
        return value instanceof int[] a ? a : null;
    }

    public long[] getLongArray(String key) {
        Object value = values.get(key);
        return value instanceof long[] a ? a : null;
    }

    /** Raw list, which may hold any element type. Never null. */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        Object value = values.get(key);
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /**
     * List filtered to its compound elements - the common case for
     * {@code block_entities}, {@code Items} and structure children.
     */
    public List<NbtCompound> getCompoundList(String key) {
        List<Object> raw = getList(key);
        if (raw.isEmpty()) return List.of();
        List<NbtCompound> compounds = new java.util.ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element instanceof NbtCompound c) compounds.add(c);
        }
        return compounds;
    }

    @Override
    public String toString() {
        return "NbtCompound" + values.keySet();
    }
}
