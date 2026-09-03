package dev.adrian.chesttracker;

import dev.adrian.chesttracker.server.ChestTrackerCommands;
import dev.adrian.chesttracker.server.TrackerService;
import dev.adrian.chesttracker.server.Trackers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Runs on every environment, including dedicated servers, so
 * nothing reachable from here may touch client-only classes.
 */
public final class ChestTracker implements ModInitializer {

    public static final String MOD_ID = "chesttracker";
    public static final Logger LOG = LoggerFactory.getLogger("ChestTracker");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Singleplayer runs an integrated server, so this is also the path
            // that gives the client full access to its own world.
            TrackerService tracker = new TrackerService(
                    server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MOD_ID));
            tracker.load();
            Trackers.setCurrent(tracker);
            LOG.info("ChestTracker ready: {} containers restored", tracker.totalContainers());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TrackerService tracker = Trackers.current();
            if (tracker != null) {
                tracker.save();
                LOG.info("ChestTracker saved {} containers", tracker.totalContainers());
            }
            Trackers.clear();
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> ChestTrackerCommands.register(dispatcher));
    }
}
