package dev.adrian.chesttracker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.adrian.chesttracker.ChestTracker;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain JSON config, no third-party config library.
 *
 * <p>Deliberately dependency-free: this mod has to work identically on two very
 * different Minecraft versions, and tying the settings to a library that may not
 * have a build for both would put that at risk. Mod Menu will eventually open a
 * screen over these values, but it stays an optional integration - the file and
 * the defaults work with nothing installed.
 */
public final class ChestTrackerConfig {

    private static ChestTrackerConfig instance;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // --- Scanning ---------------------------------------------------------

    /**
     * Scan the whole world in the background when a world is loaded.
     *
     * <p>On by default: the point of the mod is knowing what is in containers
     * you have not visited, and that cannot happen without a scan. It runs on a
     * background thread under a tick-time budget rather than at join, so a large
     * world costs throughput rather than a freeze.
     */
    public boolean scanOnWorldJoin = true;

    /** Index the contents of machines, not just their positions. */
    public boolean indexMachineContents = false;

    // --- Search -----------------------------------------------------------

    public int maxResults = 300;

    /** Whether items inside shulker boxes count as being in the outer container. */
    public boolean includeNested = true;

    // --- Highlight --------------------------------------------------------

    /** Seconds a highlight lasts while the player keeps making progress towards it. */
    public int highlightSeconds = 45;

    /** Seconds a highlight survives once the player is heading away. */
    public int highlightRecedingGraceSeconds = 10;

    public static ChestTrackerConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(ChestTracker.MOD_ID + ".json");
    }

    private static ChestTrackerConfig load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            ChestTrackerConfig fresh = new ChestTrackerConfig();
            fresh.save();
            return fresh;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            ChestTrackerConfig loaded = GSON.fromJson(reader, ChestTrackerConfig.class);
            // An empty or truncated file parses to null rather than throwing.
            return loaded == null ? new ChestTrackerConfig() : loaded;
        } catch (IOException | RuntimeException e) {
            // Never let a broken config stop the mod loading; defaults are fine.
            ChestTracker.LOG.warn("Could not read {}, using defaults: {}", path, e.toString());
            return new ChestTrackerConfig();
        }
    }

    public void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            ChestTracker.LOG.warn("Could not write {}: {}", path, e.toString());
        }
    }

    public long highlightDurationMs() {
        return Math.max(1, highlightSeconds) * 1000L;
    }

    public long highlightRecedingGraceMs() {
        return Math.max(1, highlightRecedingGraceSeconds) * 1000L;
    }
}
