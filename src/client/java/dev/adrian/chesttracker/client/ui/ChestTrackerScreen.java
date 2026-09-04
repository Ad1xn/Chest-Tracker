package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.ClientTracker;
import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.util.BlockKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Search the index, pick a container, get guided to it.
 *
 * <p>Clicking a result closes the screen deliberately: the player is going to
 * walk there, and a window in the way helps nobody. Guidance then takes over.
 */
public final class ChestTrackerScreen extends Screen {

    private static final int MAX_RESULTS = 64;
    private static final int ROW_HEIGHT = 12;
    private static final int LIST_TOP = 52;

    private EditBox search;
    private List<SearchResult> results = List.of();
    private String pending = "";
    private int scroll;
    private boolean unavailable;

    public ChestTrackerScreen() {
        super(Component.literal("ChestTracker"));
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - 150, 26, 300, 18, Component.literal("Search"));
        search.setMaxLength(64);
        search.setResponder(value -> {
            pending = value;
            refresh();
        });
        addRenderableWidget(search);
        setInitialFocus(search);

        unavailable = !ClientTracker.isAvailable();
        refresh();
    }

    private void refresh() {
        if (unavailable) return;
        String requested = pending;
        ClientTracker.search(requested, MAX_RESULTS).thenAccept(found -> {
            // A slower earlier query must not overwrite a newer one's results.
            if (!requested.equals(pending)) return;
            minecraft.execute(() -> {
                results = found;
                scroll = 0;
            });
        });
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 20) / ROW_HEIGHT);
    }

    private void draw(Gfx gfx, int mouseX, int mouseY) {
        gfx.text(font, Component.literal("ChestTracker").withStyle(ChatFormatting.BOLD),
                width / 2 - 40, 10, 0xFFFFFF);

        if (unavailable) {
            gfx.text(font, Component.literal(
                            "No index available here yet - multiplayer support is not wired up.")
                    .withStyle(ChatFormatting.GRAY), width / 2 - 150, LIST_TOP, 0xAAAAAA);
            return;
        }

        if (results.isEmpty()) {
            gfx.text(font, Component.literal(pending.isBlank()
                            ? "Nothing indexed yet - try /chesttracker scanworld."
                            : "Nothing matches \"" + pending + "\".")
                    .withStyle(ChatFormatting.GRAY), width / 2 - 150, LIST_TOP, 0xAAAAAA);
            return;
        }

        int rows = visibleRows();
        int end = Math.min(results.size(), scroll + rows);
        for (int i = scroll; i < end; i++) {
            int y = LIST_TOP + (i - scroll) * ROW_HEIGHT;
            boolean hovered = mouseY >= y && mouseY < y + ROW_HEIGHT
                    && mouseX >= width / 2 - 150 && mouseX <= width / 2 + 150;
            if (hovered) gfx.fill(width / 2 - 152, y - 1, width / 2 + 152, y + ROW_HEIGHT - 2, 0x33FFFFFF);
            gfx.text(font, describe(results.get(i)), width / 2 - 150, y, hovered ? 0xFFFFFF : 0xCCCCCC);
        }

        gfx.text(font, Component.literal(String.format("%d result(s)%s", results.size(),
                        results.size() > rows ? "  -  scroll for more" : ""))
                .withStyle(ChatFormatting.DARK_GRAY), width / 2 - 150, height - 16, 0x888888);
    }

    private Component describe(SearchResult result) {
        long pos = result.container().pos();
        String item = result.matches().isEmpty()
                ? "(container)"
                : shortName(ClientTracker.nameOf(result.matches().get(0).itemId()));

        StringBuilder line = new StringBuilder();
        if (!result.matches().isEmpty()) line.append(result.matchedCount()).append("x ");
        line.append(item)
                .append("  -  ").append(BlockKey.toString(pos))
                .append(String.format("  (%.0fm)", result.distance()))
                .append("  ").append(shortName(ClientTracker.nameOf(result.container().typeId())));

        boolean nested = result.matches().stream().anyMatch(entry -> entry.isNested());
        if (nested) line.append("  [inside a container]");
        if (result.container().origin() == Origin.NATURAL) line.append("  [natural]");
        // An unlooted chest holds no items yet, so say so rather than implying empty.
        if (!result.container().contentsKnown()) line.append("  [contents unknown]");

        return Component.literal(line.toString());
    }

    private static String shortName(String registryId) {
        int colon = registryId.indexOf(':');
        return colon < 0 ? registryId : registryId.substring(colon + 1);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && !results.isEmpty() && event.y() >= LIST_TOP) {
            int row = ((int) event.y() - LIST_TOP) / ROW_HEIGHT + scroll;
            if (row >= 0 && row < results.size()) {
                select(results.get(row));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void select(SearchResult result) {
        String label = shortName(result.matches().isEmpty()
                ? ClientTracker.nameOf(result.container().typeId())
                : ClientTracker.nameOf(result.matches().get(0).itemId()));

        ContainerHighlight.get().select(result.container().pos(),
                minecraft.player.level().dimension().identifier().toString(), label);
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int maxScroll = Math.max(0, results.size() - visibleRows());
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(deltaY)));
        return true;
    }

    // The one signature the Gfx facade cannot hide: 26.x renamed the entry point
    // and changed its parameter type as part of the deferred-rendering rework.
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
