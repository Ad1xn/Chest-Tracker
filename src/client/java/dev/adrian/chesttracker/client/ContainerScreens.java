package dev.adrian.chesttracker.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.client.ui.SearchButton;
import dev.adrian.chesttracker.client.ui.SlotHighlight;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.mixin.client.ContainerScreenAccessor;
import dev.adrian.chesttracker.mixin.client.KeyMappingAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * What this mod adds to container screens that belong to somebody else.
 *
 * <p>Two ways in, both starting from a chest the player already has open,
 * because that is where the question "where is the rest of this?" gets asked:
 * a key over the stack, and a button on the window.
 *
 * <p>The button hangs off {@code AFTER_INIT} rather than a mixin on the screen
 * itself. A screen is re-initialised on every resize, so it is repositioned for
 * free, and it lands on modded containers - subclasses of the same class -
 * without knowing they exist.
 *
 * <h2>Why the key is read twice</h2>
 *
 * <p>A key pressed while a screen is open never reaches {@code consumeClick()}:
 * vanilla only queues those when no screen is up. The documented way in is
 * Fabric's per-screen keyboard events, and it is what the mod this one replaces
 * uses, so it is tried first.
 *
 * <p>It did not fire here. That path runs inside a MixinExtras wrapper around
 * the screen's {@code keyPressed}, so anything that replaces or short-circuits
 * that call takes the hook with it - and this profile carries several input
 * mods, two of them dedicated to rewriting macOS key handling. So the window is
 * also polled once a tick, which nothing sits in front of.
 *
 * <p>Both routes end in {@link #trigger}, which ignores a second call within
 * {@link #DEBOUNCE_MS} so having both cannot search twice for one press. It
 * logs which route won, because that is the only way to learn from here which
 * one this environment actually delivers.
 */
public final class ContainerScreens {

    private ContainerScreens() {}

    /**
     * Long enough that the two routes cannot both answer one press, short
     * enough that deliberately pressing again still works.
     */
    private static final long DEBOUNCE_MS = 300;

    private static KeyMapping searchKey;
    private static boolean keyWasDown;
    private static long lastTrigger;

    /** The button on the screen currently open, or null. */
    private static SearchButton button;

    private static boolean rightWasDown;
    private static boolean draggingButton;

    /** Logged once, to settle whether screen init events arrive here at all. */
    private static boolean reportedInit;

    public static void register(KeyMapping searchHovered) {
        searchKey = searchHovered;

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;

            // init() has already run, so the window's position is settled.
            ContainerScreenAccessor access = (ContainerScreenAccessor) container;

            button = null;
            if (ChestTrackerConfig.get().containerSearchButton) {
                button = new SearchButton(
                        access.chesttracker$leftPos() + access.chesttracker$imageWidth(),
                        access.chesttracker$topPos());
                ClientCompat.addWidget(screen, button);
            }

            if (!reportedInit) {
                reportedInit = true;
                ChestTracker.LOG.info("Container screen hook installed on {}",
                        container.getClass().getName());
            }

            ClientCompat.afterScreenRender(screen, (gfx, mouseX, mouseY) ->
                    SlotHighlight.draw(gfx, container,
                            access.chesttracker$leftPos(), access.chesttracker$topPos()));

            ScreenKeyboardEvents.beforeKeyPress(screen).register((ignored, keyEvent) -> {
                if (searchHovered.matches(keyEvent)) trigger(container, "screen event");
            });
        });
    }

    /**
     * The polled route, from the client tick.
     *
     * <p>Reads the window rather than any event, so it is unaffected by whoever
     * else is handling input. Edge-triggered: holding the key searches once.
     */
    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        // Asked of the game directly rather than remembered from an event, so
        // this works even where the screen events do not arrive.
        AbstractContainerScreen<?> container =
                ClientCompat.currentScreen() instanceof AbstractContainerScreen<?> open ? open : null;
        if (container == null || searchKey == null || client.getWindow() == null) {
            // Reset outside a container, so reopening one with the key already
            // held is not mistaken for a fresh press.
            keyWasDown = false;
            return;
        }

        InputConstants.Key key = ((KeyMappingAccessor) searchKey).chesttracker$key();
        boolean down = key != null
                && key.getType() == InputConstants.Type.KEYSYM
                && key.getValue() >= 0
                && InputConstants.isKeyDown(client.getWindow(), key.getValue());

        if (down && !keyWasDown) trigger(container, "poll");
        keyWasDown = down;

        dragButton(client);
    }

    /**
     * Moves the search button while the right mouse button is held on it.
     *
     * <p>Polled, like the key, and for the same reason: a container screen
     * handles dragging for its own quick-craft and never forwards a right-drag
     * to its widgets, so the widget's own drag callbacks never arrive. Reading
     * the mouse asks nobody's permission.
     */
    private static void dragButton(Minecraft client) {
        SearchButton target = button;
        if (target == null || client.getWindow() == null) {
            rightWasDown = false;
            draggingButton = false;
            return;
        }

        boolean down = GLFW.glfwGetMouseButton(
                client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        // The mouse is in real pixels; widgets live in GUI-scaled ones.
        double scaleX = client.getWindow().getGuiScaledWidth()
                / (double) client.getWindow().getScreenWidth();
        double scaleY = client.getWindow().getGuiScaledHeight()
                / (double) client.getWindow().getScreenHeight();
        int mouseX = (int) (client.mouseHandler.xpos() * scaleX);
        int mouseY = (int) (client.mouseHandler.ypos() * scaleY);

        if (down && !rightWasDown && target.isMouseOver(mouseX, mouseY)) draggingButton = true;
        if (draggingButton && down) {
            target.setX(mouseX - SearchButton.SIZE / 2);
            target.setY(mouseY - SearchButton.SIZE / 2);
        }
        if (draggingButton && !down) {
            draggingButton = false;
            // Written once on release rather than on every frame of the drag.
            ChestTrackerConfig config = ChestTrackerConfig.get();
            config.searchButtonX = target.getX() - target.anchorX();
            config.searchButtonY = target.getY() - target.anchorY();
            config.save();
        }
        rightWasDown = down;
    }

    /**
     * Searches for whatever the cursor is over.
     *
     * @param via which route delivered the key, logged once per search so the
     *            answer to "does this environment deliver screen key events"
     *            ends up in the log rather than in somebody's guess
     */
    private static void trigger(AbstractContainerScreen<?> container, String via) {
        long now = System.currentTimeMillis();
        if (now - lastTrigger < DEBOUNCE_MS) return;
        lastTrigger = now;

        ChestTracker.LOG.info("Container search key fired via {}", via);

        // Requiring a hovered stack is also what keeps this from firing while
        // somebody types a Z into an anvil or the creative search: the cursor
        // cannot be over a slot and in a text field at once.
        Slot hovered = ((ContainerScreenAccessor) container).chesttracker$hoveredSlot();
        if (hovered == null || !hovered.hasItem()) {
            // Saying so rather than doing nothing. A silent no-op is
            // indistinguishable from the key not being bound, or from the mod
            // not being installed - which is exactly how this was first
            // reported.
            ContainerSearch.sayNothingHovered();
            return;
        }

        // Closing on a hit matches what clicking a row in the search screen
        // already does: the question is answered, and the player is about to
        // walk. Staying open would hide the guidance behind the very chest they
        // are leaving.
        if (ContainerSearch.findAndGuide(hovered.getItem())) {
            container.onClose();
        }
    }
}
