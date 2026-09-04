package dev.adrian.chesttracker.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The four things this mod needs from a container screen it did not write.
 *
 * <p>All four are {@code protected}, so there is no way to read them from
 * outside the class hierarchy. Reflection is not an option either: on 1.21.11
 * these fields ship obfuscated, and a lookup by name would work in development
 * and fail in every published build - the worst possible failure mode.
 *
 * <p>An accessor mixin costs nothing at runtime and is remapped along with the
 * rest of the jar, so it says the same thing in both environments.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {

    /** The slot under the cursor, or null. Vanilla keeps this up to date itself. */
    @Accessor("hoveredSlot")
    Slot chestindex$hoveredSlot();

    @Accessor("leftPos")
    int chestindex$leftPos();

    @Accessor("topPos")
    int chestindex$topPos();

    /** The window's width, which is not 176 for every container in the game. */
    @Accessor("imageWidth")
    int chestindex$imageWidth();
}
