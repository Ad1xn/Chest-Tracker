package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Marks the searched item inside another mod's preview of a shulker box.
 *
 * <p>The slot marks stop at the shulker: they say "it is in there" and then the
 * player opens it. But with a preview mod installed they never open it - the
 * contents are right there in the tooltip, and having marked the shulker and
 * then gone quiet at the moment the answer is on screen is worse than not
 * marking it at all.
 *
 * <p>Called from a mixin that targets ShulkerBoxTooltip by name. Nothing here
 * references that mod, and nothing in the build depends on it: this is a plain
 * method taking vanilla types, and when the mod is absent the mixin is never
 * applied and this is never called.
 */
public final class PreviewHighlight {

    private PreviewHighlight() {}

    private static final int PULSE_MS = 1400;

    /**
     * Outlines one preview slot if it holds what was searched for.
     *
     * @param x the slot's left edge, in the preview's own coordinates
     */
    public static void slot(Gfx gfx, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        if (!ChestTrackerConfig.get().highlightFoundSlots) return;

        ContainerHighlight highlight = ContainerHighlight.get();
        String wanted = highlight.searchedItemId();
        if (wanted == null || !highlight.isActive()) return;

        Item target = BuiltInRegistries.ITEM.getValue(Identifier.parse(wanted));
        if (target == null || stack.getItem() != target) return;

        int colour = pulse(0xFF000000 | ChestTrackerConfig.get().nearestColour);
        gfx.fill(x - 1, y - 1, x + 17, y, colour);
        gfx.fill(x - 1, y + 16, x + 17, y + 17, colour);
        gfx.fill(x - 1, y, x, y + 16, colour);
        gfx.fill(x + 16, y, x + 17, y + 16, colour);
    }

    /** The same beat as the slot marks in a container, so they read as one thing. */
    private static int pulse(int colour) {
        double phase = (System.currentTimeMillis() % PULSE_MS) / (double) PULSE_MS;
        float wave = (float) (0.55 + 0.45 * Math.sin(phase * Math.PI * 2));
        return (colour & 0x00FFFFFF) | ((int) (wave * 255) << 24);
    }
}
