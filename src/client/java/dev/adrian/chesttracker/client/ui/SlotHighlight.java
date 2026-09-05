package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.platform.ItemContentsCompat;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Marks the slots holding what was searched for, in whatever container is open.
 *
 * <p>Walking to the right chest is only most of the answer. Opening it leaves
 * the player scanning fifty-four slots for the thing they just asked the mod to
 * find, which is the search working right up until the moment it matters.
 *
 * <p>Three cases, and the second is the one that makes this worth having:
 * <ul>
 *   <li>the item is loose in the container - mark it;
 *   <li>the item is inside a shulker box or a bundle in the container - mark
 *       <em>that</em>, in a second colour, because opening it is the next step
 *       rather than the answer;
 *   <li>the shulker is then opened, which is just another container screen, so
 *       the first case marks the item without knowing anything new.
 * </ul>
 *
 * <p>That last point is why this reads the open screen rather than being told
 * about it: a shulker opened from an inventory, placed and opened as a block,
 * or opened by another mod's viewer all arrive here the same way.
 */
public final class SlotHighlight {

    private SlotHighlight() {}

    /** How deep to look inside container items. Two is a shulker in a shulker. */
    private static final int MAX_DEPTH = 2;

    private static final int PULSE_MS = 1400;

    public static void draw(Gfx gfx, AbstractContainerScreen<?> screen, int leftPos, int topPos) {
        if (!ChestTrackerConfig.get().highlightFoundSlots) return;

        String wanted = ContainerHighlight.get().searchedItemId();
        if (wanted == null || !ContainerHighlight.get().isActive()) return;

        ChestTrackerConfig config = ChestTrackerConfig.get();
        int direct = alpha(0xFF000000 | config.nearestColour);
        int inside = alpha(0xFF000000 | config.otherColour);

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            int colour;
            if (matches(stack, wanted)) {
                colour = direct;
            } else if (holds(stack, wanted, MAX_DEPTH)) {
                colour = inside;
            } else {
                continue;
            }

            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            // A border rather than a wash: the slot still has to show its item
            // and its stack count, and a filled overlay hides both.
            gfx.fill(x - 1, y - 1, x + 17, y, colour);
            gfx.fill(x - 1, y + 16, x + 17, y + 17, colour);
            gfx.fill(x - 1, y, x, y + 16, colour);
            gfx.fill(x + 16, y, x + 17, y + 16, colour);
        }
    }

    /**
     * Fades the mark in and out.
     *
     * <p>A static border on a slot is easy to mistake for part of the container
     * texture, especially in a modded GUI. Movement is what makes it read as
     * something the mod is saying rather than something that was always there.
     */
    private static int alpha(int colour) {
        double phase = (System.currentTimeMillis() % PULSE_MS) / (double) PULSE_MS;
        float wave = (float) (0.55 + 0.45 * Math.sin(phase * Math.PI * 2));
        return (colour & 0x00FFFFFF) | ((int) (wave * 255) << 24);
    }

    private static boolean matches(ItemStack stack, String wanted) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals(wanted);
    }

    /**
     * Whether a container item holds the wanted item, at any depth.
     *
     * <p>Reads the stack's own components rather than the index: the index
     * knows what was in a shulker when it was last seen, and the one in this
     * slot is the truth. Covers both shulker boxes and bundles, which store
     * their contents under different components.
     */
    private static boolean holds(ItemStack stack, String wanted, int depth) {
        if (depth <= 0) return false;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null
                && ItemContentsCompat.stacks(contents)
                        .anyMatch(inner -> matches(inner, wanted) || holds(inner, wanted, depth - 1))) {
            return true;
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        // itemCopyStream() rather than items(): the latter hands back
        // ItemStackTemplate on 26.2 and ItemStack on 1.21.11, while this one
        // is a Stream<ItemStack> on both and needs no shim.
        return bundle != null && bundle.itemCopyStream()
                .anyMatch(inner -> matches(inner, wanted) || holds(inner, wanted, depth - 1));
    }
}
