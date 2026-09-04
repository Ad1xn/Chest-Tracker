package dev.adrian.chesttracker.client.platform;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Client-side calls that differ between versions, in one place.
 *
 * <p>Most of the client API turned out to be stable across the 26.x rework -
 * {@code setScreenAndShow}, {@code EditBox}, {@code KeyMapping.Category} and
 * the new {@code mouseClicked(MouseButtonEvent, boolean)} signature are all
 * identical on both. Only these two genuinely moved.
 */
public final class ClientCompat {

    private ClientCompat() {}

    /**
     * Shows a message above the hotbar.
     *
     * <p>26.x split the HUD out of {@code Gui} into its own {@code Hud} class,
     * so the overlay message moved one level down.
     */
    public static void actionBar(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null) return;
        //? if >=26.1 {
        /*client.gui.hud.setOverlayMessage(message, false);
        *///?} else {
        client.gui.setOverlayMessage(message, false);
        //?}
    }

    /** Fabric API renamed its key-binding module to key-mapping for 26.x. */
    public static KeyMapping registerKeyMapping(KeyMapping mapping) {
        //? if >=26.1 {
        /*return net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(mapping);
        *///?} else {
        return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(mapping);
        //?}
    }

    /**
     * The screen currently open, or null.
     *
     * <p>26.x moved the screen off {@code Minecraft} entirely - it lives on the
     * {@code Gui} now, and there is no accessor left on {@code Minecraft} at
     * all, where 1.21.11 still has it as a public field.
     *
     * <p>Wanted so that watching for an in-container key press does not have to
     * be told which screen is open by an event that may never arrive.
     */
    public static Screen currentScreen() {
        Minecraft client = Minecraft.getInstance();
        //? if >=26.1 {
        /*return client.gui == null ? null : client.gui.screen();
        *///?} else {
        return client.screen;
        //?}
    }

    /** {@code setScreenAndShow} exists on both versions; {@code setScreen} does not. */
    public static void openScreen(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    /**
     * Adds a widget to a screen this mod did not write.
     *
     * <p>Fabric renamed this accessor for 26.x along with the rest of its screen
     * module. The list it hands back is live - what goes into it is rendered and
     * receives input - which is why this is the supported way onto somebody
     * else's screen rather than a mixin of our own.
     */
    public static void addWidget(Screen screen, AbstractWidget widget) {
        //? if >=26.1 {
        /*net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(widget);
        *///?} else {
        net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(widget);
        //?}
    }
}
