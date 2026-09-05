package dev.adrian.chesttracker.mixin.client.compat;

import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.client.ui.PreviewHighlight;
import net.minecraft.client.gui.Font;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Draws our mark over ShulkerBoxTooltip's preview slots.
 *
 * <p>Targeted by name rather than by class. ShulkerBoxTooltip is not a
 * dependency of this mod and is not on its compile path - {@code targets} takes
 * a string, and every type in the signature below is vanilla, so this compiles
 * against nothing but Minecraft. {@link ChestTrackerCompatPlugin} keeps it from
 * being applied at all when the mod is absent.
 *
 * <p>Both of their renderers are listed. They share the signature but not a
 * concrete superclass method - {@code drawSlot} is abstract on the base - so
 * hooking the base class would hook nothing.
 *
 * <p>At {@code TAIL}, so the mark lands over the item rather than under it.
 */
@Mixin(targets = {
        "com.misterpemodder.shulkerboxtooltip.impl.renderer.ModPreviewRenderer",
        "com.misterpemodder.shulkerboxtooltip.impl.renderer.VanillaPreviewRenderer",
}, remap = false)
public class PreviewRendererMixin {

    //? if >=26.1 {
    /*@Inject(method = "drawSlot", at = @At("TAIL"), require = 0)
    private void chesttracker$markSearched(ItemStack stack, int x, int y,
                                           GuiGraphicsExtractor graphics, Font font,
                                           int slot, boolean a, boolean b, CallbackInfo ci) {
        PreviewHighlight.slot(new Gfx(graphics), stack, x, y);
    }
    *///?} else {
    @Inject(method = "drawSlot", at = @At("TAIL"), require = 0)
    private void chesttracker$markSearched(ItemStack stack, int x, int y,
                                           GuiGraphics graphics, Font font,
                                           int slot, boolean a, boolean b, CallbackInfo ci) {
        PreviewHighlight.slot(new Gfx(graphics), stack, x, y);
    }
    //?}
}
