package dev.adrian.chesttracker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.net.ServerLink;
import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.client.ui.ChestTrackerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Client entrypoint: keybind, search screen, and the guidance highlight. */
public final class ChestTrackerClient implements ClientModInitializer {

    private static KeyMapping openSearch;

    @Override
    public void onInitializeClient() {
        ChestTracker.LOG.info("ChestTracker initialising (client)");

        // Receivers and connection state, so the screen knows what it is
        // talking to before the player opens it.
        ServerLink.register();

        // GRAVE matches the muscle memory of the mod this replaces.
        openSearch = ClientCompat.registerKeyMapping(new KeyMapping(
                "key.chestindex.search",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_GRAVE_ACCENT,
                // Categories became a record of an Identifier; INVENTORY is where
                // a storage-search bind belongs in the controls screen.
                KeyMapping.Category.INVENTORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openSearch.consumeClick()) {
                ClientCompat.openScreen(new ChestTrackerScreen());
            }
            ContainerHighlight.get().tick();
            // Expires the wait for a server that never announced itself, and
            // any request whose reply is never coming.
            ServerLink.tick();
        });
    }
}
