package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.client.platform.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Settings, built from vanilla widgets.
 *
 * <p>No configuration library: this mod has to behave identically on two very
 * different Minecraft versions, and depending on a library that may not have a
 * build for both would put that at risk for no real gain. Mod Menu opens this
 * screen when installed, and it is reachable without it.
 *
 * <p>Amounts are sliders rather than click-to-step buttons. Stepping through a
 * range by clicking - and wrapping back to the minimum at the top - meant
 * fifteen clicks to cross a range and no sense of where in it you were.
 *
 * <p>Everything here is read by something. An earlier version of this screen
 * offered four settings that nothing ever consulted, which is worse than not
 * offering them: it looks like the mod ignores its own configuration.
 */
public final class ConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_WIDTH = 220;

    private static final int MAX_RESULTS_CEILING = 2000;
    private static final int RESULTS_STEP = 50;

    private static final int HIGHLIGHT_MAX = 300;
    private static final int GRACE_MAX = 120;

    private final Screen parent;
    private final ChestTrackerConfig config = ChestTrackerConfig.get();

    public ConfigScreen(Screen parent) {
        super(Component.literal("ChestTracker Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = width / 2 - WIDGET_WIDTH / 2;
        int y = 40;

        addRenderableWidget(toggle(x, y, "Scan world on join",
                () -> config.scanOnWorldJoin, value -> config.scanOnWorldJoin = value));
        y += ROW_HEIGHT;

        addRenderableWidget(toggle(x, y, "Count items inside shulker boxes",
                () -> config.includeNested, value -> config.includeNested = value));
        y += ROW_HEIGHT;

        addRenderableWidget(toggle(x, y, "Show machines by default",
                () -> config.showMachines, value -> config.showMachines = value));
        y += ROW_HEIGHT;

        // Past the top of the range this reads "unlimited" rather than a
        // number: someone dragging it to the end means "stop hiding things",
        // not "exactly two thousand".
        addRenderableWidget(new IntSlider(x, y, config.maxResults, 0, MAX_RESULTS_CEILING, RESULTS_STEP) {
            @Override
            String label(int value) {
                return "Items shown: " + (value >= MAX_RESULTS_CEILING ? "unlimited" : Integer.toString(value));
            }

            @Override
            void apply(int value) {
                config.maxResults = value >= MAX_RESULTS_CEILING
                        ? ChestTrackerConfig.UNLIMITED_RESULTS : value;
            }
        });
        y += ROW_HEIGHT;

        addRenderableWidget(new IntSlider(x, y, config.highlightSeconds, 5, HIGHLIGHT_MAX, 5) {
            @Override
            String label(int value) {
                return "Guidance lasts: " + value + "s";
            }

            @Override
            void apply(int value) {
                config.highlightSeconds = value;
            }
        });
        y += ROW_HEIGHT;

        addRenderableWidget(new IntSlider(x, y, config.highlightRecedingGraceSeconds, 1, GRACE_MAX, 1) {
            @Override
            String label(int value) {
                return "Grace when walking away: " + value + "s";
            }

            @Override
            void apply(int value) {
                config.highlightRecedingGraceSeconds = value;
            }
        });
        y += ROW_HEIGHT + 10;

        addRenderableWidget(accessCycle(x, y));
        y += ROW_HEIGHT + 8;

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 60, y, 120, 20).build());
    }

    /**
     * A slider over a whole-number range.
     *
     * <p>{@link AbstractSliderButton} works in 0..1, so the conversion lives
     * here once rather than at every call site.
     */
    private abstract static class IntSlider extends AbstractSliderButton {

        private final int min;
        private final int max;
        private final int step;

        IntSlider(int x, int y, int current, int min, int max, int step) {
            super(x, y, WIDGET_WIDTH, 20, Component.empty(), fraction(current, min, max));
            this.min = min;
            this.max = max;
            this.step = step;
            updateMessage();
        }

        private static double fraction(int current, int min, int max) {
            // A stored zero means "unlimited", which sits at the top of the
            // slider rather than the bottom.
            int effective = current <= 0 ? max : current;
            return Math.min(1.0, Math.max(0.0, (effective - min) / (double) (max - min)));
        }

        private int current() {
            int raw = (int) Math.round(min + value * (max - min));
            int snapped = Math.round(raw / (float) step) * step;
            return Math.min(max, Math.max(min, snapped));
        }

        abstract String label(int value);

        abstract void apply(int value);

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label(current())));
        }

        @Override
        protected void applyValue() {
            apply(current());
        }
    }

    private Button toggle(int x, int y, String label,
                          java.util.function.BooleanSupplier getter,
                          java.util.function.Consumer<Boolean> setter) {
        return Button.builder(Component.literal(label + ": " + onOff(getter.getAsBoolean())), button -> {
            setter.accept(!getter.getAsBoolean());
            button.setMessage(Component.literal(label + ": " + onOff(getter.getAsBoolean())));
        }).bounds(x, y, WIDGET_WIDTH, 20).build();
    }

    /**
     * Cycles the multiplayer permission tier.
     *
     * <p>Does nothing in plain singleplayer, and says so: the host is never
     * gated in their own world. It starts mattering the moment that world is
     * opened to LAN, which is exactly when nobody thinks to look in a settings
     * screen - so it is labelled rather than hidden.
     */
    private Button accessCycle(int x, int y) {
        return Button.builder(Component.literal(accessLabel()), button -> {
            ChestTrackerConfig.Access[] tiers = ChestTrackerConfig.Access.values();
            int next = (config.permissionTier().ordinal() + 1) % tiers.length;
            config.permissionTier = tiers[next].name();
            button.setMessage(Component.literal(accessLabel()));
        }).bounds(x, y, WIDGET_WIDTH, 20).build();
    }

    private String accessLabel() {
        String tier = switch (config.permissionTier()) {
            case ALL -> "everyone";
            case OWNED -> "own containers";
            case OP -> "operators";
        };
        return "LAN/server guests may search: " + tier;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    @Override
    public void onClose() {
        // Written on close rather than on every keystroke, so a session of
        // fiddling produces one file write.
        config.save();
        minecraft.setScreenAndShow(parent);
    }

    private void draw(Gfx gfx) {
        gfx.text(font, Component.literal("ChestTracker Settings"),
                width / 2 - font.width("ChestTracker Settings") / 2, 18, 0xFFFFFFFF);

        String footer = Minecraft.getInstance().hasSingleplayerServer()
                ? "The last setting only applies once you open this world to LAN."
                : "Scanning happens in the background; a large world fills in over time.";
        gfx.text(font, Component.literal(footer),
                width / 2 - font.width(footer) / 2, height - 34, 0xFFA0A0A0);
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
