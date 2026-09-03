package dev.adrian.chesttracker.core.anvil;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Read-only reader for an Anvil {@code .mca} region file.
 *
 * <p>This is what makes "index the whole world" mean the whole world rather
 * than whatever is currently loaded: chunk contents are read straight off disk,
 * without asking the game to load anything. A chest's serialised block entity
 * on disk carries both its {@code Items} and its unrolled {@code LootTable}, so
 * one pass yields contents and the natural/unlooted signal together.
 *
 * <p>Deliberately independent of Minecraft. The Anvil layout has been stable
 * since 1.2.1, so one implementation covers every supported version, and it can
 * be tested against fixture files with no game running.
 *
 * <p><b>Concurrency:</b> open your own handle rather than sharing the server's.
 * A running server may be rewriting the same file, so a read can legitimately
 * come back torn; callers should treat a failure on one chunk as "skip and
 * retry later", never as a reason to abort a scan. Instances are not
 * thread-safe; give each scanning thread its own.
 */
public final class RegionFile implements Closeable {

    public static final int SECTOR_BYTES = 4096;
    private static final int CHUNKS_PER_AXIS = 32;
    private static final int HEADER_SECTORS = 2;

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;
    private static final int COMPRESSION_LZ4 = 4;
    /** High bit means the payload lives in a sibling {@code c.x.z.mcc} file. */
    private static final int EXTERNAL_FLAG = 0x80;

    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final Path path;
    private final RandomAccessFile file;
    private final int[] locations = new int[CHUNKS_PER_AXIS * CHUNKS_PER_AXIS];

    private RegionFile(Path path, RandomAccessFile file) throws IOException {
        this.path = path;
        this.file = file;
        readHeader();
    }

    public static RegionFile open(Path path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
        try {
            return new RegionFile(path, raf);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    private void readHeader() throws IOException {
        long length = file.length();
        if (length == 0) return; // A freshly created, empty region file is normal.
        if (length < SECTOR_BYTES) {
            throw new IOException("Region file shorter than its header: " + path);
        }
        file.seek(0);
        byte[] header = new byte[SECTOR_BYTES];
        file.readFully(header);
        for (int i = 0; i < locations.length; i++) {
            int base = i * 4;
            locations[i] = ((header[base] & 0xFF) << 24)
                         | ((header[base + 1] & 0xFF) << 16)
                         | ((header[base + 2] & 0xFF) << 8)
                         | (header[base + 3] & 0xFF);
        }
    }

    public Path path() {
        return path;
    }

    private static int indexOf(int localX, int localZ) {
        return (localX & 31) + (localZ & 31) * CHUNKS_PER_AXIS;
    }

    /** True if this region has data for the chunk at the given local coordinates. */
    public boolean hasChunk(int localX, int localZ) {
        return locations[indexOf(localX, localZ)] != 0;
    }

    /** Local coordinates of every chunk present, as {@code [x, z]} pairs. */
    public List<int[]> presentChunks() {
        List<int[]> present = new ArrayList<>();
        for (int z = 0; z < CHUNKS_PER_AXIS; z++) {
            for (int x = 0; x < CHUNKS_PER_AXIS; x++) {
                if (locations[indexOf(x, z)] != 0) present.add(new int[]{x, z});
            }
        }
        return present;
    }

    public int chunkCount() {
        int count = 0;
        for (int location : locations) if (location != 0) count++;
        return count;
    }

    /**
     * Reads one chunk's NBT, or null when the chunk is absent.
     *
     * @param keepKeys root-level keys to retain; everything else is skipped
     *                 unparsed. Pass null to keep the whole chunk
     */
    public NbtCompound readChunk(int localX, int localZ, Set<String> keepKeys) throws IOException {
        int location = locations[indexOf(localX, localZ)];
        if (location == 0) return null;

        int sectorOffset = location >>> 8;
        int sectorCount = location & 0xFF;
        if (sectorOffset < HEADER_SECTORS || sectorCount <= 0) {
            throw new IOException("Chunk " + localX + "," + localZ + " has a bogus header entry in " + path);
        }

        file.seek((long) sectorOffset * SECTOR_BYTES);
        int declaredLength = file.readInt();
        int compression = file.readUnsignedByte();

        if (declaredLength <= 0) {
            throw new IOException("Chunk " + localX + "," + localZ + " declares length " + declaredLength);
        }

        byte[] payload;
        if ((compression & EXTERNAL_FLAG) != 0) {
            payload = readExternal(localX, localZ);
            compression &= ~EXTERNAL_FLAG;
        } else {
            int payloadLength = declaredLength - 1; // the compression byte is counted
            long available = file.length() - file.getFilePointer();
            if (payloadLength > available) {
                throw new IOException("Chunk " + localX + "," + localZ + " runs past the end of " + path
                        + " (wants " + payloadLength + ", " + available + " left)");
            }
            payload = new byte[payloadLength];
            file.readFully(payload);
        }

        try (InputStream in = decompress(compression, payload)) {
            return NbtReader.readNamedRoot(new DataInputStream(in), keepKeys);
        }
    }

    private byte[] readExternal(int localX, int localZ) throws IOException {
        // Chunks too big for 255 sectors are written beside the region file.
        int[] region = regionCoords();
        int chunkX = region[0] * CHUNKS_PER_AXIS + (localX & 31);
        int chunkZ = region[1] * CHUNKS_PER_AXIS + (localZ & 31);
        Path external = path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
        if (!Files.exists(external)) {
            throw new IOException("Chunk " + chunkX + "," + chunkZ + " is marked external but " + external + " is missing");
        }
        return Files.readAllBytes(external);
    }

    private static InputStream decompress(int compression, byte[] payload) throws IOException {
        ByteArrayInputStream raw = new ByteArrayInputStream(payload);
        return switch (compression) {
            case COMPRESSION_GZIP -> new GZIPInputStream(raw);
            case COMPRESSION_ZLIB -> new InflaterInputStream(raw);
            case COMPRESSION_NONE -> raw;
            case COMPRESSION_LZ4 -> throw new IOException(
                    "Chunk uses LZ4 compression, which needs a codec this build does not bundle. "
                    + "Set region-file-compression to deflate in server.properties, or the chunk will be skipped.");
            default -> throw new IOException("Unknown chunk compression type " + compression);
        };
    }

    /** Region coordinates parsed from the filename. */
    public int[] regionCoords() throws IOException {
        int[] coords = parseCoords(path.getFileName().toString());
        if (coords == null) throw new IOException("Not a region filename: " + path.getFileName());
        return coords;
    }

    /** Parses {@code r.X.Z.mca}, or null if the name does not match. */
    public static int[] parseCoords(String fileName) {
        Matcher matcher = NAME.matcher(fileName);
        if (!matcher.matches()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    public static boolean isRegionFileName(String fileName) {
        return NAME.matcher(fileName).matches();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
