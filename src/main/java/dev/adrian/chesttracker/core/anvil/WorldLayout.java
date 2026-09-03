package dev.adrian.chesttracker.core.anvil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the region directories of a saved world.
 *
 * <p>Minecraft 26.1 reorganised world storage: vanilla dimensions moved out of
 * the world root and under {@code dimensions/minecraft/…}, alongside where
 * datapack dimensions already lived. Hard-coding either layout breaks on the
 * other, and this must behave identically for the Fabric mod and a future
 * plugin, so both are probed and the results merged.
 *
 * <p>Probing rather than branching on the game version is deliberate: it also
 * copes with a partially migrated world, a world moved between versions, and
 * whatever the next reshuffle looks like.
 */
public final class WorldLayout {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String THE_NETHER = "minecraft:the_nether";
    public static final String THE_END = "minecraft:the_end";

    private WorldLayout() {}

    /**
     * A dimension's region directory.
     *
     * @param dimensionId namespaced id, e.g. {@code minecraft:overworld}
     * @param regionDir   directory holding this dimension's {@code .mca} files
     */
    public record DimensionRegions(String dimensionId, Path regionDir) {}

    /**
     * Every dimension with a region directory under {@code worldRoot}.
     *
     * <p>Legacy locations are probed first so that in a half-migrated world the
     * classic path wins for a given id and the modern one is ignored as a
     * duplicate rather than listed twice.
     */
    public static List<DimensionRegions> discover(Path worldRoot) throws IOException {
        Map<String, DimensionRegions> found = new LinkedHashMap<>();

        // Pre-26 layout: overworld at the root, nether and end in DIM folders.
        addIfRegionDir(found, OVERWORLD, worldRoot.resolve("region"));
        addIfRegionDir(found, THE_NETHER, worldRoot.resolve("DIM-1").resolve("region"));
        addIfRegionDir(found, THE_END, worldRoot.resolve("DIM1").resolve("region"));

        // dimensions/<namespace>/<path>/region - datapack dimensions have always
        // lived here, and from 26.1 the vanilla ones do too.
        Path dimensionsRoot = worldRoot.resolve("dimensions");
        if (Files.isDirectory(dimensionsRoot)) {
            try (Stream<Path> namespaces = Files.list(dimensionsRoot)) {
                for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> paths = Files.list(namespace)) {
                        for (Path dimension : paths.filter(Files::isDirectory).toList()) {
                            String id = namespace.getFileName() + ":" + dimension.getFileName();
                            addIfRegionDir(found, id, dimension.resolve("region"));
                        }
                    }
                }
            }
        }

        return List.copyOf(found.values());
    }

    private static void addIfRegionDir(Map<String, DimensionRegions> found, String id, Path regionDir) {
        if (!found.containsKey(id) && Files.isDirectory(regionDir)) {
            found.put(id, new DimensionRegions(id, regionDir));
        }
    }

    /** Region directory for one dimension id, or null if it has none. */
    public static Path regionDirFor(Path worldRoot, String dimensionId) throws IOException {
        for (DimensionRegions dimension : discover(worldRoot)) {
            if (dimension.dimensionId().equals(dimensionId)) return dimension.regionDir();
        }
        return null;
    }

    /** Every {@code .mca} file in a region directory, sorted for stable scan order. */
    public static List<Path> regionFiles(Path regionDir) throws IOException {
        if (!Files.isDirectory(regionDir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(regionDir)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry) && RegionFile.isRegionFileName(entry.getFileName().toString())) {
                    files.add(entry);
                }
            }
        }
        files.sort(Path::compareTo);
        return files;
    }

    /** True if this looks like a saved world rather than an arbitrary folder. */
    public static boolean isWorldRoot(Path candidate) throws IOException {
        if (!Files.isDirectory(candidate)) return false;
        return Files.isRegularFile(candidate.resolve("level.dat")) || !discover(candidate).isEmpty();
    }
}
