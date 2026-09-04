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

    // --- Multiplayer ------------------------------------------------------

    /**
     * Who may query the index over the network.
     *
     * <p>Named rather than numbered so the file stays readable, and parsed
     * leniently so a typo degrades to the safe end rather than to the open one.
     */
    public enum Access {
        /** Anyone on the server. Right for a private world among friends. */
        ALL,
        /** Only containers the asking player placed. */
        OWNED,
        /** Operators only. */
        OP;

        /** Unknown text means OP: an unreadable setting must not open the index. */
        public static Access parse(String value) {
            if (value == null) return OP;
            for (Access access : values()) {
                if (access.name().equalsIgnoreCase(value.trim())) return access;
            }
            return OP;
        }
    }

    /**
     * Who may query this copy of the mod over the network.
     *
     * <p>Defaults to {@code OP} because a full world index is effectively loot
     * x-ray: on a shared server it tells anyone where every unopened generated
     * chest is, and what is in everybody else's base. Opening that up has to be
     * the server owner's deliberate choice, so the default is the closed one.
     *
     * <p>Applies to every player who arrives over a connection, which includes
     * guests in a world opened to LAN - a LAN host wanting to share the index
     * sets this to {@code ALL}. The host themselves is never gated: their
     * screen reads the integrated server directly and never goes near this.
     */
    public String permissionTier = Access.OP.name();

    public Access permissionTier() {
        return Access.parse(permissionTier);
    }

    /**
     * How long the client waits for a server's hello before deciding there is
     * no server-side index.
     *
     * <p>Long enough to cover a join under load, short enough that a vanilla
     * server does not leave the screen saying "connecting" for any noticeable
     * time.
     */
    public int serverHelloTimeoutMs = 3000;

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
