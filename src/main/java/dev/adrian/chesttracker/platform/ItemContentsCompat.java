package dev.adrian.chesttracker.platform;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.stream.Stream;

/**
 * Version-conditional access to the contents of a container item, such as a
 * shulker box sitting inside a chest.
 *
 * <p>This is the kind of difference the platform layer exists to absorb.
 * {@code ItemContainerContents} was reworked after 1.21.11: {@code stream()}
 * became {@code allItemsCopyStream()}, and {@code nonEmptyItems()} changed its
 * element type from {@code ItemStack} to {@code ItemStackTemplate}, so it is not
 * a drop-in either. Both versions still expose a {@code Stream<ItemStack>}, so
 * that is the shape the rest of the mod codes against.
 */
public final class ItemContentsCompat {

    private ItemContentsCompat() {}

    /** Every stack held by a container item, as a version-independent stream. */
    public static Stream<ItemStack> stacks(ItemContainerContents contents) {
        //? if >=26.1 {
        /*return contents.allItemsCopyStream();
        *///?} else {
        return contents.stream();
        //?}
    }
}
