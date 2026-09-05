package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Picks the two colours the in-world markers are drawn in.
 *
 * <p>A palette, not channel sliders. Three sliders is the colour a programmer
 * would offer and nobody would use: it asks the player to know what a colour is
 * made of in order to choose one. Swatches ask them to point at the one they
 * want.
 *
 * <p>The colours offered are deliberately not a uniform spread of the spectrum.
 * They are the ones that survive being drawn over Minecraft - saturated and
 * bright, skipping the sky blues and the sun yellows and the greens that sit in
 * grass, because a marker's whole job is to not be the background.
 */
public final class HighlightColourScreen extends Screen {

    /**
     * Colours worth marking something with.
     *
     * <p>Two rows of eight. The first is the bright end, for the nearest match;
     * the second the same hues softened, which reads as "and also these" beside
     * the first rather than competing with it.
     */
    private static final int[] PALETTE = {
            0xFF2BD0, 0xFF3B7A, 0xFF6A2B, 0xFFC400, 0x9CFF2B, 0x2BFF9C, 0x2BE5FF, 0xB478FF,
            0xC41FA0, 0xC42F5F, 0xC4521F, 0xC49600, 0x78C41F, 0x1FC478, 0x1FAFC4, 0x8A5AC4,
    };

    private static final int SWATCH = 18;
    private static final int SWATCH_GAP = 3;
    private static final int COLUMNS = 8;

    private final Screen parent;
    private final ChestTrackerConfig config = ChestTrackerConfig.get();

    /** Which of the two colours a click on the palette will set. */
    private boolean editingNearest = true;

    public HighlightColourScreen(Screen parent) {
        super(Component.literal("Highlight colours"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = paletteTop() + rows() * (SWATCH + SWATCH_GAP) + 10;

        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), button -> {
            ChestTrackerConfig fresh = new ChestTrackerConfig();
            config.nearestColour = fresh.nearestColour;
            config.otherColour = fresh.otherColour;
        }).bounds(width / 2 - 100, y, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 + 5, y, 95, 20).build());
    }

    private static int rows() {
        return (PALETTE.length + COLUMNS - 1) / COLUMNS;
    }

    private int paletteWidth() {
        return COLUMNS * SWATCH + (COLUMNS - 1) * SWATCH_GAP;
    }

    private int paletteLeft() {
        return width / 2 - paletteWidth() / 2;
    }

    private int paletteTop() {
        return 82;
    }

    /** The two big swatches at the top, which are also the pair of tabs. */
    private int targetX(boolean nearest) {
        return width / 2 - 62 + (nearest ? 0 : 64);
    }

    private static final int TARGET_Y = 34;
    private static final int TARGET_W = 60;
    private static final int TARGET_H = 34;

    private void draw(Gfx gfx, int mouseX, int mouseY) {
        centred(gfx, "Highlight colours", 14, 0xFFFFFFFF);

        for (boolean nearest : new boolean[] {true, false}) {
            int x = targetX(nearest);
            int colour = nearest ? config.nearestColour : config.otherColour;
            boolean active = nearest == editingNearest;

            gfx.fill(x - 2, TARGET_Y - 2, x + TARGET_W + 2, TARGET_Y + TARGET_H + 2,
                    active ? 0xFFFFFFFF : 0xFF3A3A3A);
            gfx.fill(x, TARGET_Y, x + TARGET_W, TARGET_Y + TARGET_H, 0xFF000000 | colour);

            String label = nearest ? "Nearest" : "Others";
            gfx.text(font, Component.literal(label),
                    x + TARGET_W / 2 - font.width(label) / 2, TARGET_Y + TARGET_H + 5,
                    active ? 0xFFFFFFFF : 0xFFA0A0A0);
        }

        int left = paletteLeft();
        int top = paletteTop();
        for (int i = 0; i < PALETTE.length; i++) {
            int x = left + (i % COLUMNS) * (SWATCH + SWATCH_GAP);
            int y = top + (i / COLUMNS) * (SWATCH + SWATCH_GAP);
            boolean hovered = mouseX >= x && mouseX < x + SWATCH && mouseY >= y && mouseY < y + SWATCH;
            boolean chosen = PALETTE[i] == (editingNearest ? config.nearestColour : config.otherColour);

            if (chosen || hovered) {
                gfx.fill(x - 2, y - 2, x + SWATCH + 2, y + SWATCH + 2,
                        chosen ? 0xFFFFFFFF : 0xFFB0B0B0);
            }
            gfx.fill(x, y, x + SWATCH, y + SWATCH, 0xFF000000 | PALETTE[i]);
        }

        centred(gfx, "Pick a swatch to set the colour shown above", paletteTop() - 14, 0xFFA0A0A0);
    }

    private void centred(Gfx gfx, String text, int y, int colour) {
        gfx.text(font, Component.literal(text), width / 2 - font.width(text) / 2, y, colour);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        for (boolean nearest : new boolean[] {true, false}) {
            int x = targetX(nearest);
            if (mouseX >= x && mouseX < x + TARGET_W
                    && mouseY >= TARGET_Y && mouseY < TARGET_Y + TARGET_H) {
                editingNearest = nearest;
                return true;
            }
        }

        int left = paletteLeft();
        int top = paletteTop();
        for (int i = 0; i < PALETTE.length; i++) {
            int x = left + (i % COLUMNS) * (SWATCH + SWATCH_GAP);
            int y = top + (i / COLUMNS) * (SWATCH + SWATCH_GAP);
            if (mouseX >= x && mouseX < x + SWATCH && mouseY >= y && mouseY < y + SWATCH) {
                if (editingNearest) {
                    config.nearestColour = PALETTE[i];
                } else {
                    config.otherColour = PALETTE[i];
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.setScreenAndShow(parent);
    }

    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        draw(new Gfx(graphics), mouseX, mouseY);
    }
    *///?} else {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        draw(new Gfx(graphics), mouseX, mouseY);
    }
    //?}
}
