package dev.adrian.chesttracker.core.anvil;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads NBT. Read-only, and independent of Minecraft so the same parser serves
 * every supported game version and can be tested without a game.
 *
 * <p>The important feature is {@link #readNamedRoot(DataInput, Set)}: it takes
 * the set of root keys worth keeping and <em>skips</em> everything else without
 * allocating. A chunk is dominated by {@code sections} - a block-state palette
 * plus a 4096-entry packed long array per subchunk - and {@code Heightmaps},
 * none of which this mod ever reads. Parsing those anyway would multiply the
 * cost of a whole-world scan for nothing.
 *
 * <p>A depth limit guards against a malformed or hostile file sending the
 * recursive parser into a stack overflow.
 */
public final class NbtReader {

    private static final int MAX_DEPTH = 512;

    /** Guards against absurd allocations from a corrupt length field. */
    private static final int MAX_ARRAY_LENGTH = 1 << 26;

    private NbtReader() {}

    /** Reads a complete root compound, keeping everything. */
    public static NbtCompound read(InputStream in) throws IOException {
        return read((DataInput) new DataInputStream(in));
    }

    public static NbtCompound read(DataInput in) throws IOException {
        return readNamedRoot(in, null);
    }

    /**
     * Reads the root compound, retaining only {@code keepKeys} and skipping
     * every other top-level entry.
     *
     * @param keepKeys keys to retain, or null to keep everything
     */
    public static NbtCompound readNamedRoot(DataInput in, Set<String> keepKeys) throws IOException {
        int rootType = in.readUnsignedByte();
        if (rootType == NbtType.END) return new NbtCompound();
        if (rootType != NbtType.COMPOUND) {
            throw new IOException("NBT root must be a compound, got type " + rootType);
        }
        skipString(in); // root name, conventionally empty
        return readCompoundBody(in, keepKeys, 0);
    }

    private static NbtCompound readCompoundBody(DataInput in, Set<String> keepKeys, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested beyond " + MAX_DEPTH + " levels");

        Map<String, Object> values = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == NbtType.END) break;

            if (keepKeys == null) {
                String name = in.readUTF();
                values.put(name, readPayload(in, type, depth + 1));
            } else {
                // Read the name, then decide whether the payload is worth parsing.
                String name = in.readUTF();
                if (keepKeys.contains(name)) {
                    values.put(name, readPayload(in, type, depth + 1));
                } else {
                    skipPayload(in, type, depth + 1);
                }
            }
        }
        return new NbtCompound(values);
    }

    private static Object readPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested beyond " + MAX_DEPTH + " levels");

        switch (type) {
            case NbtType.BYTE: return in.readByte();
            case NbtType.SHORT: return in.readShort();
            case NbtType.INT: return in.readInt();
            case NbtType.LONG: return in.readLong();
            case NbtType.FLOAT: return in.readFloat();
            case NbtType.DOUBLE: return in.readDouble();
            case NbtType.STRING: return in.readUTF();
            case NbtType.BYTE_ARRAY: {
                byte[] array = new byte[checkedLength(in.readInt(), 1)];
                in.readFully(array);
                return array;
            }
            case NbtType.INT_ARRAY: {
                int[] array = new int[checkedLength(in.readInt(), 4)];
                for (int i = 0; i < array.length; i++) array[i] = in.readInt();
                return array;
            }
            case NbtType.LONG_ARRAY: {
                long[] array = new long[checkedLength(in.readInt(), 8)];
                for (int i = 0; i < array.length; i++) array[i] = in.readLong();
                return array;
            }
            case NbtType.LIST: {
                int elementType = in.readUnsignedByte();
                int length = checkedLength(in.readInt(), 1);
                List<Object> items = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    items.add(readPayload(in, elementType, depth + 1));
                }
                return items;
            }
            case NbtType.COMPOUND:
                // Nested compounds keep everything: the filter is a root-level
                // decision, and once we have chosen to read block_entities we
                // want all of it.
                return readCompoundBody(in, null, depth + 1);
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    /** Advances past a payload without building objects for it. */
    private static void skipPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested beyond " + MAX_DEPTH + " levels");

        switch (type) {
            case NbtType.BYTE -> skipFully(in, 1);
            case NbtType.SHORT -> skipFully(in, 2);
            case NbtType.INT, NbtType.FLOAT -> skipFully(in, 4);
            case NbtType.LONG, NbtType.DOUBLE -> skipFully(in, 8);
            case NbtType.STRING -> skipString(in);
            case NbtType.BYTE_ARRAY -> skipFully(in, (long) checkedLength(in.readInt(), 1));
            case NbtType.INT_ARRAY -> skipFully(in, 4L * checkedLength(in.readInt(), 4));
            case NbtType.LONG_ARRAY -> skipFully(in, 8L * checkedLength(in.readInt(), 8));
            case NbtType.LIST -> {
                int elementType = in.readUnsignedByte();
                int length = checkedLength(in.readInt(), 1);
                // Fixed-width elements skip in one jump instead of one per item;
                // this is the case that matters for `sections`.
                int width = fixedWidth(elementType);
                if (width > 0) {
                    skipFully(in, (long) width * length);
                } else {
                    for (int i = 0; i < length; i++) skipPayload(in, elementType, depth + 1);
                }
            }
            case NbtType.COMPOUND -> {
                while (true) {
                    int entryType = in.readUnsignedByte();
                    if (entryType == NbtType.END) break;
                    skipString(in);
                    skipPayload(in, entryType, depth + 1);
                }
            }
            case NbtType.END -> { }
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    /** Byte width of a fixed-size tag, or -1 for variable-length ones. */
    private static int fixedWidth(int type) {
        return switch (type) {
            case NbtType.BYTE -> 1;
            case NbtType.SHORT -> 2;
            case NbtType.INT, NbtType.FLOAT -> 4;
            case NbtType.LONG, NbtType.DOUBLE -> 8;
            case NbtType.END -> 0;
            default -> -1;
        };
    }

    private static void skipString(DataInput in) throws IOException {
        int length = in.readUnsignedShort();
        skipFully(in, length);
    }

    private static void skipFully(DataInput in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            // skipBytes takes an int and may legitimately skip fewer than asked.
            int step = (int) Math.min(remaining, Integer.MAX_VALUE);
            int skipped = in.skipBytes(step);
            if (skipped <= 0) {
                // Some DataInput implementations refuse to skip at the end of a
                // buffer; fall back to reading so we still make progress.
                in.readByte();
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static int checkedLength(int length, int elementBytes) throws IOException {
        if (length < 0) throw new IOException("Negative NBT array length " + length);
        if ((long) length * elementBytes > MAX_ARRAY_LENGTH * 8L) {
            throw new IOException("Implausible NBT array length " + length + " (file is corrupt)");
        }
        return length;
    }
}
