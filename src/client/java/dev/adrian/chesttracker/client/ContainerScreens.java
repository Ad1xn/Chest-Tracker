package dev.adrian.chesttracker.client;

import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.client.ui.SearchButton;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.mixin.client.ContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * What this mod adds to container screens that belong to somebody else.
 *
 * <p>Two ways in, both starting from a chest the player already has open,
 * because that is where the question "where is the rest of this?" gets asked:
 * a key over the stack, and a button on the window.
 *
 * <p>Everything hangs off {@code AFTER_INIT} rather than a mixin on the screen
 * itself. A screen is re-initialised on every resize, so this runs again with
 * the new geometry for free, and it applies to modded containers - which are
 * subclasses of the same class - without knowing they exist.
 */
public final class ContainerScreens {

    private ContainerScreens() {}

    public static void register(KeyMapping searchHovered) {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;

            // init() has already run, so the window's position is settled.
            ContainerScreenAccessor access = (ContainerScreenAccessor) container;

            if (ChestTrackerConfig.get().containerSearchButton) {
                ClientCompat.addWidget(screen, new SearchButton(
                        access.chesttracker$leftPos() + access.chesttracker$imageWidth(),
                        access.chesttracker$topPos()));
            }

            ScreenKeyboardEvents.afterKeyPress(screen).register((ignored, keyEvent) -> {
                if (!searchHovered.matches(keyEvent)) return;

                // Requiring a hovered stack is also what keeps this from firing
                // while somebody types a Z into an anvil or the creative search:
                // the cursor cannot be over a slot and in a text field at once.
                Slot hovered = access.chesttracker$hoveredSlot();
                if (hovered == null || !hovered.hasItem()) return;

                ContainerSearch.findAndGuide(hovered.getItem());
            });
        });
    }
}
