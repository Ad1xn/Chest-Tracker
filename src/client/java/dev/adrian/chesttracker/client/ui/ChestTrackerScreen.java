package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.ClientTracker;
import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.util.BlockKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Search the index, pick a container, get guided to it.
 *
 * <p>The layout is item-first, not container-first. "Where is my redstone" is
 * the question players actually ask, and a flat list of containers makes them
 * add up the totals themselves. So the left pane totals each item across the
 * world, and selecting one shows the containers holding it, nearest first.
 *
 * <p>Clicking a location closes the screen deliberately: the player is about to
 * walk there, and a window in the way helps nobody.
 */
public final class ChestTrackerScreen extends Screen {

    // Colours are ARGB. A bare 0xFFFFFF has an alpha of zero and draws
    // perfectly invisibly, which is exactly as confusing as it sounds.
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_NORMAL = 0xFFD0D0D0;
    private static final int TEXT_MUTED = 0xFF909090;
    private static final int TEXT_ACCENT = 0xFF7FD4FF;
    private static final int PANEL = 0xE0141414;
    private static final int PANEL_EDGE = 0xFF2E2E2E;
    private static final int PANE = 0xFF1C1C1C;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = 0x40FFCC66;

    private static final int MAX_ITEMS = 300;
    private static final int MAX_CONTAINERS = 64;

    private static final int ITEM_ROW = 18;
    private static final int DETAIL_ROW = 11;
    private static final int PADDING = 8;

    /** Registry lookups are not free and item rows redraw every frame. */
    private static final Map<Integer, ItemStack> ICON_CACHE = new HashMap<>();

    private EditBox search;
    private String pending = "";

    private List<WorldIndex.ItemSummary> items = List.of();
    private List<SearchResult> containers = List.of();
    private int selectedItemId = -1;
    private int itemScroll;

    private boolean unavailable;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listW;

    public ChestTrackerScreen() {
        super(Component.literal("ChestTracker"));
    }

    @Override
    protected void init() {
        panelW = Math.min(460, width - 40);
        panelH = Math.min(280, height - 60);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        listW = (int) (panelW * 0.46);

        search = new EditBox(font, panelX + PADDING, panelY + 22, panelW - PADDING * 2, 16,
                Component.literal("Search"));
        search.setMaxLength(64);
        search.setHint(Component.literal("Search items..."));
        search.setResponder(value -> {
            pending = value;
            refreshItems();
        });
        addRenderableWidget(search);
        setInitialFocus(search);

        unavailable = !ClientTracker.isAvailable();
        refreshItems();
    }

    private void refreshItems() {
        if (unavailable) return;
        String requested = pending;
        ClientTracker.summarise(requested, MAX_ITEMS).thenAccept(found -> minecraft.execute(() -> {
            // A slow earlier query must not overwrite a newer one's results.
            if (!requested.equals(pending)) return;
            items = found;
            itemScroll = 0;
            selectedItemId = -1;
            containers = List.of();
        }));
    }

    private void selectItem(int itemId) {
        selectedItemId = itemId;
        containers = List.of();
        ClientTracker.containersFor(itemId, MAX_CONTAINERS).thenAccept(found -> minecraft.execute(() -> {
            if (selectedItemId == itemId) containers = found;
        }));
    }

    // --- geometry ----------------------------------------------------------

    private int listTop() {
        return panelY + 44;
    }

    private int listBottom() {
        return panelY + panelH - PADDING;
    }

    private int visibleItemRows() {
        return Math.max(1, (listBottom() - listTop()) / ITEM_ROW);
    }

    private int detailX() {
        return panelX + listW + PADDING * 2;
    }

    // --- drawing -----------------------------------------------------------

    private void draw(Gfx gfx, int mouseX, int mouseY) {
        gfx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, PANEL_EDGE);
        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);

        gfx.text(font, Component.literal("ChestTracker").withStyle(ChatFormatting.BOLD),
                panelX + PADDING, panelY + 8, TEXT_PRIMARY);

        if (unavailable) {
            gfx.text(font, Component.literal("No index here yet - multiplayer is not wired up.")
                    .withStyle(ChatFormatting.GRAY), panelX + PADDING, listTop(), TEXT_MUTED);
            return;
        }

        gfx.fill(panelX + PADDING - 2, listTop() - 2, panelX + PADDING + listW, listBottom(), PANE);
        drawItems(gfx, mouseX, mouseY);
        drawDetail(gfx, mouseX, mouseY);
    }

    private void drawItems(Gfx gfx, int mouseX, int mouseY) {
        if (items.isEmpty()) {
            gfx.text(font, Component.literal(pending.isBlank()
                            ? "Nothing indexed yet."
                            : "No match for \"" + pending + "\""),
                    panelX + PADDING, listTop() + 4, TEXT_MUTED);
            if (pending.isBlank()) {
                gfx.text(font, Component.literal("Run /chesttracker scanworld"),
                        panelX + PADDING, listTop() + 16, TEXT_MUTED);
            }
            return;
        }

        int rows = visibleItemRows();
        int end = Math.min(items.size(), itemScroll + rows);
        for (int i = itemScroll; i < end; i++) {
            WorldIndex.ItemSummary summary = items.get(i);
            int y = listTop() + (i - itemScroll) * ITEM_ROW;
            boolean hovered = inRow(mouseX, mouseY, panelX + PADDING, y, listW, ITEM_ROW);

            if (summary.itemId() == selectedItemId) {
                gfx.fill(panelX + PADDING - 2, y - 1, panelX + PADDING + listW, y + ITEM_ROW - 2, ROW_SELECTED);
            } else if (hovered) {
                gfx.fill(panelX + PADDING - 2, y - 1, panelX + PADDING + listW, y + ITEM_ROW - 2, ROW_HOVER);
            }

            gfx.item(iconFor(summary.itemId()), panelX + PADDING, y);
            gfx.text(font, Component.literal(displayName(summary.itemId())),
                    panelX + PADDING + 20, y + 1, hovered ? TEXT_PRIMARY : TEXT_NORMAL);
            gfx.text(font, Component.literal(String.format("%,d  in %d",
                            summary.totalCount(), summary.containerCount())),
                    panelX + PADDING + 20, y + 10, TEXT_MUTED);
        }

        if (items.size() > rows) {
            gfx.text(font, Component.literal(String.format("%d/%d", end, items.size())),
                    panelX + PADDING + listW - 34, panelY + 32, TEXT_MUTED);
        }
    }

    private void drawDetail(Gfx gfx, int mouseX, int mouseY) {
        int x = detailX();
        if (selectedItemId < 0) {
            gfx.text(font, Component.literal("Pick an item").withStyle(ChatFormatting.ITALIC),
                    x, listTop() + 4, TEXT_MUTED);
            return;
        }

        gfx.text(font, Component.literal(displayName(selectedItemId)), x, listTop(), TEXT_PRIMARY);

        if (containers.isEmpty()) {
            gfx.text(font, Component.literal("Looking..."), x, listTop() + 14, TEXT_MUTED);
            return;
        }

        gfx.text(font, Component.literal(containers.size() + " container(s), nearest first"),
                x, listTop() + 12, TEXT_MUTED);

        int y = listTop() + 26;
        for (SearchResult result : containers) {
            if (y + DETAIL_ROW > listBottom()) break;
            boolean hovered = inRow(mouseX, mouseY, x, y, panelX + panelW - PADDING - x, DETAIL_ROW);
            if (hovered) gfx.fill(x - 2, y - 1, panelX + panelW - PADDING, y + DETAIL_ROW - 1, ROW_HOVER);

            gfx.text(font, Component.literal(describe(result)), x, y, hovered ? TEXT_ACCENT : TEXT_NORMAL);
            y += DETAIL_ROW;
        }

        gfx.text(font, Component.literal("Click a location to be guided there"),
                x, listBottom() - 9, TEXT_MUTED);
    }

    private String describe(SearchResult result) {
        StringBuilder line = new StringBuilder();
        line.append(result.matchedCount()).append("x  ")
                .append(shortName(ClientTracker.nameOf(result.container().typeId())))
                .append("  ").append(BlockKey.toString(result.container().pos()))
                .append(String.format("  %.0fm", result.distance()));

        if (result.matches().stream().anyMatch(entry -> entry.isNested())) line.append("  (nested)");
        if (result.container().origin() == Origin.NATURAL) line.append("  (natural)");
        return line.toString();
    }

    private static boolean inRow(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x - 2 && mouseX <= x + w && mouseY >= y - 1 && mouseY < y + h - 1;
    }

    // --- item display ------------------------------------------------------

    private static ItemStack iconFor(int paletteId) {
        return ICON_CACHE.computeIfAbsent(paletteId, id -> {
            Identifier identifier = Identifier.tryParse(ClientTracker.nameOf(id));
            if (identifier == null) return ItemStack.EMPTY;
            Item item = BuiltInRegistries.ITEM.getValue(identifier);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    /** The player-facing name, falling back to the registry id for unknown items. */
    private static String displayName(int paletteId) {
        ItemStack stack = iconFor(paletteId);
        if (stack.isEmpty()) return shortName(ClientTracker.nameOf(paletteId));
        return stack.getHoverName().getString();
    }

    private static String shortName(String registryId) {
        int colon = registryId.indexOf(':');
        return colon < 0 ? registryId : registryId.substring(colon + 1);
    }

    // --- input -------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && !unavailable) {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();

            if (mouseX <= panelX + PADDING + listW && mouseY >= listTop() && mouseY < listBottom()) {
                int row = (mouseY - listTop()) / ITEM_ROW + itemScroll;
                if (row >= 0 && row < items.size()) {
                    selectItem(items.get(row).itemId());
                    return true;
                }
            }

            if (mouseX > detailX() - 4 && !containers.isEmpty()) {
                int row = (mouseY - (listTop() + 26)) / DETAIL_ROW;
                if (row >= 0 && row < containers.size()) {
                    guideTo(containers.get(row));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void guideTo(SearchResult result) {
        ContainerHighlight.get().select(result.container().pos(),
                minecraft.player.level().dimension().identifier().toString(),
                displayName(selectedItemId));
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int maxScroll = Math.max(0, items.size() - visibleItemRows());
        itemScroll = Math.max(0, Math.min(maxScroll, itemScroll - (int) Math.signum(deltaY)));
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
