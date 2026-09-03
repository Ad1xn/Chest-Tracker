package dev.adrian.chesttracker.core.anvil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT writer, for building fixtures. Test-only: the mod never writes
 * NBT, so shipping a writer in main would be dead weight.
 */
final class NbtTestWriter {

    private NbtTestWriter() {}

    /** Builder for a compound, so tests read like the structure they describe. */
    static final class Compound {
        final Map<String, Object> values = new LinkedHashMap<>();

        Compound put(String key, Object value) {
            values.put(key, value);
            return this;
        }
    }

    static Compound compound() {
        return new Compound();
    }

    static byte[] toBytes(Compound root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(NbtType.COMPOUND);
            out.writeUTF("");
            writeCompoundBody(out, root);
        }
        return bytes.toByteArray();
    }

    private static void writeCompoundBody(DataOutputStream out, Compound compound) throws IOException {
        for (Map.Entry<String, Object> entry : compound.values.entrySet()) {
            int type = typeOf(entry.getValue());
            out.writeByte(type);
            out.writeUTF(entry.getKey());
            writePayload(out, type, entry.getValue());
        }
        out.writeByte(NbtType.END);
    }

    private static int typeOf(Object value) {
        if (value instanceof Byte) return NbtType.BYTE;
        if (value instanceof Short) return NbtType.SHORT;
        if (value instanceof Integer) return NbtType.INT;
        if (value instanceof Long) return NbtType.LONG;
        if (value instanceof Float) return NbtType.FLOAT;
        if (value instanceof Double) return NbtType.DOUBLE;
        if (value instanceof byte[]) return NbtType.BYTE_ARRAY;
        if (value instanceof String) return NbtType.STRING;
        if (value instanceof List) return NbtType.LIST;
        if (value instanceof Compound) return NbtType.COMPOUND;
        if (value instanceof int[]) return NbtType.INT_ARRAY;
        if (value instanceof long[]) return NbtType.LONG_ARRAY;
        throw new IllegalArgumentException("No NBT type for " + value.getClass());
    }

    private static void writePayload(DataOutputStream out, int type, Object value) throws IOException {
        switch (type) {
            case NbtType.BYTE -> out.writeByte((Byte) value);
            case NbtType.SHORT -> out.writeShort((Short) value);
            case NbtType.INT -> out.writeInt((Integer) value);
            case NbtType.LONG -> out.writeLong((Long) value);
            case NbtType.FLOAT -> out.writeFloat((Float) value);
            case NbtType.DOUBLE -> out.writeDouble((Double) value);
            case NbtType.STRING -> out.writeUTF((String) value);
            case NbtType.BYTE_ARRAY -> {
                byte[] array = (byte[]) value;
                out.writeInt(array.length);
                out.write(array);
            }
            case NbtType.INT_ARRAY -> {
                int[] array = (int[]) value;
                out.writeInt(array.length);
                for (int element : array) out.writeInt(element);
            }
            case NbtType.LONG_ARRAY -> {
                long[] array = (long[]) value;
                out.writeInt(array.length);
                for (long element : array) out.writeLong(element);
            }
            case NbtType.LIST -> {
                List<?> list = (List<?>) value;
                int elementType = list.isEmpty() ? NbtType.END : typeOf(list.get(0));
                out.writeByte(elementType);
                out.writeInt(list.size());
                for (Object element : list) writePayload(out, elementType, element);
            }
            case NbtType.COMPOUND -> writeCompoundBody(out, (Compound) value);
            default -> throw new IllegalArgumentException("Cannot write NBT type " + type);
        }
    }
}
