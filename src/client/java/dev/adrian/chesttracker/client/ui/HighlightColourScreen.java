package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Picks the two colours the in-world markers are drawn in.
 *
 * <p>Its own screen rather than six more rows on the settings page, which had
 * already grown a second column. Sliders rather than a colour wheel: a wheel is
 * a lot of drawing code for a choice made once, and channels are what the value
 * actually is - the config file stores {@code 0xRRGGBB} and this is a legible
 * way to reach it.
 *
 * <p>A swatch of each colour sits beside its sliders, because three numbers are
 * not a colour to anyone reading them.
 */
public final class HighlightColourScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int SLIDER_WIDTH = 180;
    private static final int SWATCH = 40;

    private final Screen parent;
    private final ChestTrackerConfig config = ChestTrackerConfig.get();

    public HighlightColourScreen(Screen parent) {
        super(Component.literal("Highlight colours"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = width / 2 - SLIDER_WIDTH / 2 + SWATCH / 2;
        int y = 46;

        y = channels(x, y, true);
        y += 12;
        y = channels(x, y, false);

        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), button -> {
            ChestTrackerConfig fresh = new ChestTrackerConfig();
            config.nearestColour = fresh.nearestColour;
            config.otherColour = fresh.otherColour;
            rebuildWidgets();
        }).bounds(width / 2 - 100, y + 6, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 + 5, y + 6, 95, 20).build());
    }

    /** One row of red, green and blue for one of the two colours. */
    private int channels(int x, int y, boolean nearest) {
        for (int shift : new int[] {16, 8, 0}) {
            addRenderableWidget(new ChannelSlider(x, y, shift, nearest));
            y += ROW_HEIGHT;
        }
        return y;
    }

    private int colourOf(boolean nearest) {
        return nearest ? config.nearestColour : config.otherColour;
    }

    private void setColour(boolean nearest, int value) {
        if (nearest) {
            config.nearestColour = value;
        } else {
            config.otherColour = value;
        }
    }

    private final class ChannelSlider extends ConfigScreen.IntSlider {

        private final int shift;
        private final boolean nearest;

        ChannelSlider(int x, int y, int shift, boolean nearest) {
            super(x, y, SLIDER_WIDTH, (colourOf(nearest) >> shift) & 0xFF, 0, 255, 1);
            this.shift = shift;
            this.nearest = nearest;
            updateMessage();
        }

        @Override
        String label(int value) {
            String channel = switch (shift) {
                case 16 -> "Red";
                case 8 -> "Green";
                default -> "Blue";
            };
            return (nearest ? "Nearest " : "Others ") + channel + ": " + value;
        }

        @Override
        void apply(int value) {
            int packed = colourOf(nearest) & ~(0xFF << shift);
            setColour(nearest, packed | (value << shift));
        }
    }

    private void draw(Gfx gfx) {
        gfx.text(font, Component.literal("Highlight colours"),
                width / 2 - font.width("Highlight colours") / 2, 18, 0xFFFFFFFF);

        int x = width / 2 - SLIDER_WIDTH / 2 - SWATCH / 2 - 6;
        swatch(gfx, x, 46, config.nearestColour);
        swatch(gfx, x, 46 + ROW_HEIGHT * 3 + 12, config.otherColour);
    }

    private void swatch(Gfx gfx, int x, int y, int colour) {
        gfx.fill(x - 1, y - 1, x + SWATCH + 1, y + SWATCH + 1, 0xFF000000);
        gfx.fill(x, y, x + SWATCH, y + SWATCH, 0xFF000000 | colour);
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
        draw(new Gfx(graphics));
    }
    *///?} else {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        draw(new Gfx(graphics));
    }
    //?}
}
