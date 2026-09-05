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
    private static final int WIDGET_WIDTH = 200;

    /** Gap between the two columns. */
    private static final int COLUMN_GAP = 8;

    /**
     * Rows before the list moves to the second column.
     *
     * <p>One column ran out of screen. At a common GUI scale the window is
     * about 240 rows tall, and eleven settings plus a Done button did not fit -
     * the bottom of the list simply left the screen, which is a worse way to
     * hide a setting than not having it.
     */
    private static final int ROWS_PER_COLUMN = 7;

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

    /** Widgets placed so far, which decides where the next one goes. */
    private int placed;

    private int columnX() {
        int left = width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2;
        return placed < ROWS_PER_COLUMN ? left : left + WIDGET_WIDTH + COLUMN_GAP;
    }

    private int rowY() {
        return 40 + (placed % ROWS_PER_COLUMN) * ROW_HEIGHT;
    }

    /** Places the next widget, wrapping into the second column when the first fills. */
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T place(T widget) {
        addRenderableWidget(widget);
        placed++;
        return widget;
    }

    @Override
    protected void init() {
        placed = 0;
        int x = columnX();
        int y = rowY();

        place(toggle(x, y, "Scan world on join",
                () -> config.scanOnWorldJoin, value -> config.scanOnWorldJoin = value));

        x = columnX(); y = rowY();
        place(toggle(x, y, "Count items inside shulker boxes",
                () -> config.includeNested, value -> config.includeNested = value));

        x = columnX(); y = rowY();
        place(toggle(x, y, "Show machines by default",
                () -> config.showMachines, value -> config.showMachines = value));

        x = columnX(); y = rowY();

        // Past the top of the range this reads "unlimited" rather than a
        // number: someone dragging it to the end means "stop hiding things",
        // not "exactly two thousand".
        place(new IntSlider(x, y, config.maxResults, 0, MAX_RESULTS_CEILING, RESULTS_STEP) {
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

        x = columnX(); y = rowY();
        place(displayCycle(x, y));

        x = columnX(); y = rowY();
        place(new IntSlider(x, y, config.highlightSeconds, 5, HIGHLIGHT_MAX, 5) {
            @Override
            String label(int value) {
                return "Guidance lasts: " + value + "s";
            }

            @Override
            void apply(int value) {
                config.highlightSeconds = value;
            }
        });

        x = columnX(); y = rowY();
        place(new IntSlider(x, y, config.highlightRecedingGraceSeconds, 1, GRACE_MAX, 1) {
            @Override
            String label(int value) {
                return "Grace when walking away: " + value + "s";
            }

            @Override
            void apply(int value) {
                config.highlightRecedingGraceSeconds = value;
            }
        });

        x = columnX(); y = rowY();
        place(toggle(x, y, "Trail of marks above matches",
                () -> config.guideBeam, value -> config.guideBeam = value));

        x = columnX(); y = rowY();
        place(toggle(x, y, "Open a match already in reach",
                () -> config.openInReach, value -> config.openInReach = value));

        x = columnX(); y = rowY();
        place(toggle(x, y, "Turn to face a match",
                () -> config.turnToTarget, value -> config.turnToTarget = value));

        x = columnX(); y = rowY();
        place(toggle(x, y, "Search button on containers",
                () -> config.containerSearchButton, value -> config.containerSearchButton = value));

        x = columnX(); y = rowY();
        place(nestedCycle(x, y));

        x = columnX(); y = rowY();
        place(accessCycle(x, y));

        int bottom = 40 + ROWS_PER_COLUMN * ROW_HEIGHT + 12;
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 60, bottom, 120, 20).build());
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

    /** Cycles how a highlight is shown: boxes, text above the hotbar, both, or neither. */
    private Button displayCycle(int x, int y) {
        return Button.builder(Component.literal(displayLabel()), button -> {
            ChestTrackerConfig.Display[] modes = ChestTrackerConfig.Display.values();
            int next = (config.highlightDisplay().ordinal() + 1) % modes.length;
            config.highlightDisplay = modes[next].name();
            button.setMessage(Component.literal(displayLabel()));
        }).bounds(x, y, WIDGET_WIDTH, 20).build();
    }

    private String displayLabel() {
        String mode = switch (config.highlightDisplay()) {
            case BOXES -> "boxes in the world";
            case ACTION_BAR -> "text above the hotbar";
            case BOTH -> "boxes and text";
            case NONE -> "nothing";
        };
        return "Show found containers as: " + mode;
    }

    /** Cycles how the grid shows an item that is sealed inside shulker boxes. */
    private Button nestedCycle(int x, int y) {
        return Button.builder(Component.literal(nestedLabel()), button -> {
            ChestTrackerConfig.Nested[] modes = ChestTrackerConfig.Nested.values();
            int next = (config.nestedDisplay().ordinal() + 1) % modes.length;
            config.nestedDisplay = modes[next].name();
            button.setMessage(Component.literal(nestedLabel()));
        }).bounds(x, y, WIDGET_WIDTH, 20).build();
    }

    private String nestedLabel() {
        String mode = switch (config.nestedDisplay()) {
            case MARK -> "a corner mark";
            case TOOLTIP -> "a hover panel";
            case BOTH -> "mark and panel";
            case NONE -> "not at all";
        };
        return "Items in shulkers: " + mode;
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
                ? "The access setting only applies once you open this world to LAN."
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
