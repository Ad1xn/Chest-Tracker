package dev.adrian.chesttracker.platform;

import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.store.StringPalette;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a live {@link Container} into the index's own representation.
 *
 * <p>The offline region scanner produces exactly the same {@link StackEntry}
 * shape from serialised NBT, so the two sources are interchangeable and the
 * index never has to care which one a record came from.
 */
public final class LiveContainerReader {

    /** Deeper than this is broken or hostile data, not a real shulker chain. */
    private static final int MAX_NESTING = 8;

    private LiveContainerReader() {}

    /** Flattens a container's stacks, descending into nested storage. */
    public static List<StackEntry> read(Container container, StringPalette palette) {
        List<StackEntry> entries = new ArrayList<>();
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            appendStack(stack, entries, 0, palette);
        }
        return entries;
    }

    private static void appendStack(ItemStack stack, List<StackEntry> out, int depth, StringPalette palette) {
        if (depth > MAX_NESTING) return;

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        out.add(new StackEntry(
                palette.intern(itemId),
                stack.getCount(),
                depth,
                customName == null ? null : customName.getString()));

        // A shulker box in a chest: its contents are searchable too, flattened
        // with a depth so the UI can say where the item actually is.
        ItemContainerContents nested = stack.get(DataComponents.CONTAINER);
        if (nested != null) {
            ItemContentsCompat.stacks(nested).forEach(inner -> appendStack(inner, out, depth + 1, palette));
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            // itemCopyStream() is identical on both target versions, unlike
            // items(), whose element type changed after 1.21.11.
            bundle.itemCopyStream().forEach(inner -> appendStack(inner, out, depth + 1, palette));
        }
    }
}
