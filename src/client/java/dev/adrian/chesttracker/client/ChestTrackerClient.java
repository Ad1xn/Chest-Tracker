package dev.adrian.chesttracker.client;

import dev.adrian.chesttracker.ChestTracker;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint: search UI, keybinds, highlights, vanilla-server fallback index. */
public final class ChestTrackerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ChestTracker.LOG.info("ChestTracker initialising (client)");
    }
}
