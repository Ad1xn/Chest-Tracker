package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.client.platform.Gfx;
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
 */
public final class ConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_WIDTH = 220;

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

        addRenderableWidget(toggle(x, y, "Index machine contents",
                () -> config.indexMachineContents, value -> config.indexMachineContents = value));
        y += ROW_HEIGHT;

        addRenderableWidget(toggle(x, y, "Count items inside shulker boxes",
                () -> config.includeNested, value -> config.includeNested = value));
        y += ROW_HEIGHT;

        addRenderableWidget(stepper(x, y, "Max results", () -> config.maxResults,
                value -> config.maxResults = value, 50, 50, 2000));
        y += ROW_HEIGHT;

        addRenderableWidget(stepper(x, y, "Highlight seconds", () -> config.highlightSeconds,
                value -> config.highlightSeconds = value, 15, 15, 300));
        y += ROW_HEIGHT;

        addRenderableWidget(stepper(x, y, "Grace when walking away", () -> config.highlightRecedingGraceSeconds,
                value -> config.highlightRecedingGraceSeconds = value, 5, 5, 120));
        y += ROW_HEIGHT;

        // Only has an effect where this copy of the mod is the server - hosting
        // a LAN world, or running a dedicated server with a GUI. It is shown
        // anyway because the alternative is hand-editing JSON to open up a
        // server, and the setting is the one people most need to find.
        addRenderableWidget(accessCycle(x, y));
        y += ROW_HEIGHT + 8;

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 60, y, 120, 20).build());
    }

    private Button toggle(int x, int y, String label,
                          java.util.function.BooleanSupplier getter,
                          java.util.function.Consumer<Boolean> setter) {
        Button[] holder = new Button[1];
        holder[0] = Button.builder(Component.literal(label + ": " + onOff(getter.getAsBoolean())), button -> {
            setter.accept(!getter.getAsBoolean());
            button.setMessage(Component.literal(label + ": " + onOff(getter.getAsBoolean())));
        }).bounds(x, y, WIDGET_WIDTH, 20).build();
        return holder[0];
    }

    /** Left-click steps up, right-click is not available on a Button, so it wraps. */
    private Button stepper(int x, int y, String label,
                           java.util.function.IntSupplier getter,
                           java.util.function.IntConsumer setter,
                           int step, int min, int max) {
        return Button.builder(Component.literal(label + ": " + getter.getAsInt()), button -> {
            int next = getter.getAsInt() + step;
            if (next > max) next = min;
            setter.accept(next);
            button.setMessage(Component.literal(label + ": " + getter.getAsInt()));
        }).bounds(x, y, WIDGET_WIDTH, 20).build();
    }

    /** Cycles the multiplayer permission tier. */
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
        return "Players served: " + tier;
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
        gfx.text(font, Component.literal("Scanning happens in the background; a large world fills in over time."),
                width / 2 - 200, height - 34, 0xFFA0A0A0);
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
