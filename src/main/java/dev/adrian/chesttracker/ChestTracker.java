package dev.adrian.chesttracker;

import dev.adrian.chesttracker.server.ChestTrackerCommands;
import dev.adrian.chesttracker.server.TrackerService;
import dev.adrian.chesttracker.server.Trackers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.server.scan.LiveScanner;
import dev.adrian.chesttracker.server.scan.RegionScanner;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Runs on every environment, including dedicated servers, so
 * nothing reachable from here may touch client-only classes.
 */
public final class ChestTracker implements ModInitializer {

    // The internal id must differ from the original Chest Tracker's, or Fabric
    // resolves the collision by id and this mod silently never loads. The
    // display name stays "ChestTracker"; only the id and its derived paths move.
    public static final String MOD_ID = "chestindex";
    public static final Logger LOG = LoggerFactory.getLogger("ChestTracker");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Singleplayer runs an integrated server, so this is also the path
            // that gives the client full access to its own world.
            TrackerService tracker = new TrackerService(
                    server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MOD_ID));
            tracker.load();
            Trackers.setCurrent(tracker, server);
            LOG.info("ChestTracker ready: {} containers restored", tracker.totalContainers());

            if (ChestTrackerConfig.get().scanOnWorldJoin) {
                // Background, never at join: a full region scan of a large world
                // would freeze the game for as long as it takes. This yields
                // under load and simply finishes when it finishes.
                RegionScanner scanner = Trackers.regionScanner();
                if (scanner != null) {
                    scanner.start(server.getWorldPath(LevelResource.ROOT), server.overworld().getGameTime());
                }
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TrackerService tracker = Trackers.current();
            if (tracker != null) {
                tracker.save();
                LOG.info("ChestTracker saved {} containers", tracker.totalContainers());
            }
            Trackers.clear();
        });

        // Applying scan results happens here, on the server thread, under a
        // per-tick budget. The scanner thread only ever reads and parses.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Containers whose contents changed since last tick. Without this the
            // index only learns contents when a chunk unloads, so filling a chest
            // you just placed would never show up.
            Trackers.drainDirty();

            RegionScanner scanner = Trackers.regionScanner();
            if (scanner == null) return;
            long tickNanos = (long) (server.getCurrentSmoothedTickTime() * 1_000_000.0f);
            scanner.drain(Trackers::isChunkLoaded, Trackers::liveScanChunk, tickNanos);
        });

        // A chunk about to unload is frozen from here on, so this is the one
        // moment its contents are worth capturing: exactly once, and final.
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            TrackerService tracker = Trackers.current();
            if (tracker == null) return;
            new LiveScanner(tracker).scanChunk(world, chunk, Trackers.dimensionId(world));
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> ChestTrackerCommands.register(dispatcher));
    }
}
