package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * The little magnifier that sits on a container window.
 *
 * <p>Opening a chest is the moment a player wonders where the rest of their
 * stuff is, so the search is offered there rather than only behind a key they
 * have to remember.
 *
 * <p>Right-drag moves it and the new place is saved. That is not decoration:
 * the top-right corner is exactly where several popular mods put their own
 * buttons, and a fixed position would mean this one sits under somebody else's
 * for the players who have both. Left-click opens, right-drag moves - split by
 * button rather than by whether the mouse travelled far enough, because a
 * gesture that sometimes opens a window and sometimes does not is worse than
 * one the player has to learn.
 *
 * <p>The drag itself is driven from {@code ContainerScreens} by polling the
 * mouse, not by this widget's drag callbacks. A container screen handles
 * dragging for its own quick-craft and never forwards a right-drag on to its
 * widgets, so those callbacks are simply never delivered - which is exactly how
 * it was reported.
 *
 * <p>Drawn with flat colours rather than sampled from a texture, unlike the
 * search screen: this button lands on a furnace, a hopper, a modded machine -
 * whatever screen the player opened - and there is no one texture to take the
 * pixels from. Vanilla's container palette is the same grey in every one of
 * them, so a fixed colour is the honest match here.
 */
public final class SearchButton extends AbstractWidget {

    public static final int SIZE = 12;

    // Vanilla's container palette, shared by every container GUI in the game.
    private static final int PANEL = 0xFFC6C6C6;
    private static final int BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int BEVEL_DARK = 0xFF555555;
    private static final int ICON = 0xFF404040;
    private static final int HOVER = 0x80FFFFFF;

    /** The container window's top-right corner, which the offset is measured from. */
    private final int anchorX;
    private final int anchorY;

    public SearchButton(int anchorX, int anchorY) {
        super(0, 0, SIZE, SIZE, Component.literal("Search containers"));
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        applyConfiguredPosition();
        setTooltip(Tooltip.create(Component.literal("Search containers  (right-drag to move)")));
    }

    private void applyConfiguredPosition() {
        ChestTrackerConfig config = ChestTrackerConfig.get();
        setX(anchorX + config.searchButtonX);
        setY(anchorY + config.searchButtonY);
    }

    // --- drawing ------------------------------------------------------------

    private void draw(Gfx gfx, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();

        gfx.fill(x, y, x + SIZE, y + SIZE, BEVEL_DARK);
        gfx.fill(x, y, x + SIZE - 1, y + SIZE - 1, PANEL);
        gfx.fill(x, y, x + SIZE - 1, y + 1, BEVEL_LIGHT);
        gfx.fill(x, y, x + 1, y + SIZE - 1, BEVEL_LIGHT);
        if (isMouseOver(mouseX, mouseY)) {
            gfx.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, HOVER);
        }
        drawMagnifier(gfx, x + 2, y + 2);
    }

    /**
     * An eight-pixel magnifying glass: a ring and a handle.
     *
     * <p>Drawn rather than blitted because there is no vanilla sprite for it
     * that is named the same on both supported versions, and a bundled texture
     * would be one more thing a resource pack could not touch.
     */
    private void drawMagnifier(Gfx gfx, int x, int y) {
        gfx.fill(x + 1, y, x + 4, y + 1, ICON);
        gfx.fill(x + 1, y + 4, x + 4, y + 5, ICON);
        gfx.fill(x, y + 1, x + 1, y + 4, ICON);
        gfx.fill(x + 4, y + 1, x + 5, y + 4, ICON);
        gfx.fill(x + 4, y + 4, x + 6, y + 6, ICON);
        gfx.fill(x + 5, y + 5, x + 8, y + 8, ICON);
    }

    // --- input --------------------------------------------------------------

    /** Where the anchor is, so the drag poll can turn a position into an offset. */
    public int anchorX() {
        return anchorX;
    }

    public int anchorY() {
        return anchorY;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!visible || !isMouseOver(event.x(), event.y())) return false;

        if (event.button() == 0) {
            playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
            ClientCompat.openScreen(new ChestTrackerScreen());
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    // The one signature the Gfx facade cannot hide: 26.x renamed the widget's
    // render entry point and changed its parameter type as part of the
    // deferred-rendering rework.
    //? if >=26.1 {
    /*@Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        draw(new Gfx(graphics), mouseX, mouseY);
    }
    *///?} else {
    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        draw(new Gfx(graphics), mouseX, mouseY);
    }
    //?}
}
