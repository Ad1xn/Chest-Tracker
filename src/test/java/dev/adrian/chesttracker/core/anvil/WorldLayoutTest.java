package dev.adrian.chesttracker.core.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.1 moved vanilla dimensions out of the world root and under
 * {@code dimensions/minecraft/…}. Both layouts have to resolve, because the mod
 * targets versions on either side of that change and must behave identically.
 */
class WorldLayoutTest {

    private static Path dirs(Path root, String... segments) throws IOException {
        Path path = root;
        for (String segment : segments) path = path.resolve(segment);
        Files.createDirectories(path);
        return path;
    }

    private static List<String> idsOf(List<WorldLayout.DimensionRegions> dimensions) {
        return dimensions.stream().map(WorldLayout.DimensionRegions::dimensionId).sorted().toList();
    }

    @Test
    void findsDimensionsInThePre26Layout(@TempDir Path world) throws IOException {
        dirs(world, "region");
        dirs(world, "DIM-1", "region");
        dirs(world, "DIM1", "region");

        List<WorldLayout.DimensionRegions> found = WorldLayout.discover(world);

        assertEquals(List.of(WorldLayout.OVERWORLD, WorldLayout.THE_END, WorldLayout.THE_NETHER), idsOf(found));
        assertEquals(world.resolve("region"), WorldLayout.regionDirFor(world, WorldLayout.OVERWORLD));
    }

    @Test
    void findsDimensionsInThe26Layout(@TempDir Path world) throws IOException {
        dirs(world, "dimensions", "minecraft", "overworld", "region");
        dirs(world, "dimensions", "minecraft", "the_nether", "region");
        dirs(world, "dimensions", "minecraft", "the_end", "region");

        List<WorldLayout.DimensionRegions> found = WorldLayout.discover(world);

        assertEquals(List.of(WorldLayout.OVERWORLD, WorldLayout.THE_END, WorldLayout.THE_NETHER), idsOf(found));
        assertEquals(world.resolve("dimensions/minecraft/overworld/region"),
                WorldLayout.regionDirFor(world, WorldLayout.OVERWORLD));
    }

    @Test
    void findsDatapackDimensions(@TempDir Path world) throws IOException {
        dirs(world, "region");
        dirs(world, "dimensions", "mypack", "mining", "region");

        assertEquals(List.of("minecraft:overworld", "mypack:mining"), idsOf(WorldLayout.discover(world)));
    }

    @Test
    void prefersTheLegacyPathInAHalfMigratedWorld(@TempDir Path world) throws IOException {
        // A world caught mid-migration has both; it must be listed once, not twice.
        dirs(world, "region");
        dirs(world, "dimensions", "minecraft", "overworld", "region");

        List<WorldLayout.DimensionRegions> found = WorldLayout.discover(world);

        assertEquals(1, found.size());
        assertEquals(world.resolve("region"), found.get(0).regionDir());
    }

    @Test
    void ignoresDimensionFoldersWithoutRegionData(@TempDir Path world) throws IOException {
        dirs(world, "region");
        dirs(world, "dimensions", "minecraft", "overworld", "entities");
        dirs(world, "DIM-1", "data");

        assertEquals(List.of(WorldLayout.OVERWORLD), idsOf(WorldLayout.discover(world)));
    }

    @Test
    void returnsNothingForAnUnrelatedDirectory(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("documents"));
        assertTrue(WorldLayout.discover(dir).isEmpty());
        assertNull(WorldLayout.regionDirFor(dir, WorldLayout.OVERWORLD));
        assertFalse(WorldLayout.isWorldRoot(dir));
    }

    @Test
    void recognisesAWorldRoot(@TempDir Path world) throws IOException {
        Files.createFile(world.resolve("level.dat"));
        assertTrue(WorldLayout.isWorldRoot(world));
    }

    @Test
    void listsOnlyRegionFilesInStableOrder(@TempDir Path world) throws IOException {
        Path region = dirs(world, "region");
        for (String name : List.of("r.1.0.mca", "r.-1.0.mca", "r.0.0.mca", "notes.txt", "r.0.0.mca.tmp")) {
            Files.createFile(region.resolve(name));
        }

        List<Path> files = WorldLayout.regionFiles(region);

        assertEquals(List.of("r.-1.0.mca", "r.0.0.mca", "r.1.0.mca"),
                files.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void listingAMissingRegionDirectoryIsEmptyNotAnError(@TempDir Path dir) throws IOException {
        assertTrue(WorldLayout.regionFiles(dir.resolve("nope")).isEmpty());
    }
}
