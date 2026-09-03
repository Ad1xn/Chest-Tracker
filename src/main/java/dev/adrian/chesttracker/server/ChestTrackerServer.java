package dev.adrian.chesttracker.server;

import dev.adrian.chesttracker.ChestTracker;
import net.fabricmc.api.DedicatedServerModInitializer;

/** Dedicated-server entrypoint. Singleplayer uses the integrated server via the common path. */
public final class ChestTrackerServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ChestTracker.LOG.info("ChestTracker initialising (dedicated server)");
    }
}
