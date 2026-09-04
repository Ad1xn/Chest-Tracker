package dev.adrian.chesttracker.client.platform;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * The one place the GUI rendering rewrite is allowed to show.
 *
 * <p>26.x replaced immediate-mode screen drawing with a deferred render-state
 * model, almost certainly to support the Vulkan backend. The changes are
 * pervasive but mechanical - every call this mod needs kept its signature and
 * changed only its name:
 *
 * <pre>
 *   GuiGraphics        -&gt; GuiGraphicsExtractor
 *   drawString(...)    -&gt; text(...)
 *   renderItem(...)    -&gt; item(...)
 *   Screen.render(...) -&gt; Screen.extractRenderState(...)
 * </pre>
 *
 * <p>Wrapping them here means the screens themselves are written once. The only
 * other place that has to know is the single overridden entry point in
 * {@link dev.adrian.chesttracker.client.ui.ChestTrackerScreen}, because that is
 * a method signature and cannot be hidden behind a facade.
 */
public final class Gfx {

    //? if >=26.1 {
    /*private final GuiGraphicsExtractor raw;

    public Gfx(GuiGraphicsExtractor raw) {
        this.raw = raw;
    }

    public void text(Font font, Component text, int x, int y, int colour) {
        raw.text(font, text, x, y, colour);
    }

    public void text(Font font, String text, int x, int y, int colour) {
        raw.text(font, text, x, y, colour);
    }

    public void fill(int x1, int y1, int x2, int y2, int colour) {
        raw.fill(x1, y1, x2, y2, colour);
    }

    public void item(ItemStack stack, int x, int y) {
        raw.item(stack, x, y);
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        raw.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        raw.disableScissor();
    }
    *///?} else {
    private final GuiGraphics raw;

    public Gfx(GuiGraphics raw) {
        this.raw = raw;
    }

    public void text(Font font, Component text, int x, int y, int colour) {
        raw.drawString(font, text, x, y, colour);
    }

    public void text(Font font, String text, int x, int y, int colour) {
        raw.drawString(font, text, x, y, colour);
    }

    public void fill(int x1, int y1, int x2, int y2, int colour) {
        raw.fill(x1, y1, x2, y2, colour);
    }

    public void item(ItemStack stack, int x, int y) {
        raw.renderItem(stack, x, y);
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        raw.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        raw.disableScissor();
    }
    //?}
}
