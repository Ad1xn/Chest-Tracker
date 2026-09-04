package dev.adrian.chesttracker.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.net.ServerLink;
import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.client.platform.WorldHighlightHook;
import dev.adrian.chesttracker.client.ui.ChestTrackerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Client entrypoint: keybind, search screen, and the guidance highlight. */
public final class ChestTrackerClient implements ClientModInitializer {

    private static KeyMapping openSearch;
    private static KeyMapping searchHovered;

    @Override
    public void onInitializeClient() {
        ChestTracker.LOG.info("ChestTracker initialising (client)");

        // Receivers and connection state, so the screen knows what it is
        // talking to before the player opens it.
        ServerLink.register();

        // Boxes around tracked containers. The only part of the mod that talks
        // to the world renderer, and the only place the two targets diverge
        // enough to need a whole separate registration.
        WorldHighlightHook.register();

        // GRAVE matches the muscle memory of the mod this replaces.
        openSearch = ClientCompat.registerKeyMapping(new KeyMapping(
                "key.chest-tracker.search",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_GRAVE_ACCENT,
                // Categories became a record of an Identifier; INVENTORY is where
                // a storage-search bind belongs in the controls screen.
                KeyMapping.Category.INVENTORY));

        // Z is free in vanilla and sits under the left hand while the right one
        // is on the mouse, which is the posture this is used in: cursor over a
        // stack in a chest, asking where the rest of it is.
        searchHovered = ClientCompat.registerKeyMapping(new KeyMapping(
                "key.chest-tracker.search_hovered",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                KeyMapping.Category.INVENTORY));

        // Keybinds do not fire while a screen is open, so the in-container half
        // of this listens on the screen itself rather than on the client tick.
        ContainerScreens.register(searchHovered);

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
