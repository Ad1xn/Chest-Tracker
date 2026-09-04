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
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client entrypoint: keybind, search screen, and the guidance highlight. */
public final class ChestTrackerClient implements ClientModInitializer {

    /**
     * The mod's own group in the controls screen.
     *
     * <p>Every other mod with more than one binding has one, and two loose
     * entries filed under vanilla's Inventory heading are hard to find among
     * the thirty already there.
     *
     * <p>The translation key is derived from the id, so this reads
     * {@code key.category.chest-tracker.title} in the language file.
     */
    private static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(ChestTracker.MOD_ID, "title"));

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
                CATEGORY));

        // Z is free in vanilla and sits under the left hand while the right one
        // is on the mouse, which is the posture this is used in: cursor over a
        // stack in a chest, asking where the rest of it is.
        searchHovered = ClientCompat.registerKeyMapping(new KeyMapping(
                "key.chest-tracker.search_hovered",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                CATEGORY));

        // Keybinds do not fire while a screen is open, so the in-container half
        // of this listens on the screen itself - and, because that turned out
        // not to be delivered here, polls the window too. See ContainerScreens.
        ContainerScreens.register(searchHovered);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openSearch.consumeClick()) {
                ClientCompat.openScreen(new ChestTrackerScreen());
            }
            ContainerHighlight.get().tick();
            ContainerScreens.tick();
            // Expires the wait for a server that never announced itself, and
            // any request whose reply is never coming.
            ServerLink.tick();
        });
    }
}
