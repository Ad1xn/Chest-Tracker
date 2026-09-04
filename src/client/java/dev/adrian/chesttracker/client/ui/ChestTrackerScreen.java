package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.ClientTracker;
import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.util.BlockKey;
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
 * A container-shaped view of everything you own.
 *
 * <p>Laid out like a vanilla chest - nine slots per row, item icons with stack
 * counts - because that is the shape players already read fluently, and because
 * a one-item-per-row list means scrolling forever once a world has a few hundred
 * distinct items. Nine across turns that into a glance.
 *
 * <p>Two views in one window: the grid of items, and, once you click one, the
 * containers holding it. Clicking a location closes the screen and hands over to
 * guidance, because the player is about to walk there.
 */
public final class ChestTrackerScreen extends Screen {

    // Vanilla container palette, so this sits alongside a real chest GUI rather
    // than looking like a mod window pasted on top.
    private static final int PANEL_FILL = 0xFFC6C6C6;
    private static final int BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int BEVEL_DARK = 0xFF555555;
    private static final int BORDER = 0xFF000000;
    private static final int SLOT_FILL = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int TEXT_TITLE = 0xFF404040;
    private static final int TEXT_BODY = 0xFF404040;
    private static final int TEXT_LIGHT = 0xFFFFFFFF;
    private static final int SLOT_HOVER = 0x80FFFFFF;
    private static final int SLOT_SELECTED = 0x80FFCC44;
    private static final int ROW_HOVER = 0x40000000;

    private static final int COLS = 9;
    private static final int SLOT = 18;
    private static final int PADDING = 7;
    private static final int TITLE_H = 13;
    private static final int SEARCH_H = 14;
    private static final int FOOTER_H = 12;
    private static final int DETAIL_ROW = 11;

    private static final int MAX_ITEMS = 600;
    private static final int MAX_CONTAINERS = 64;

    /** Registry lookups are not free and slots redraw every frame. */
    private static final Map<Integer, ItemStack> ICON_CACHE = new HashMap<>();

    private EditBox search;
    private String pending = "";

    private List<WorldIndex.ItemSummary> items = List.of();
    private List<SearchResult> containers = List.of();
    private int selectedItemId = -1;
    private int scrollRow;
    private boolean unavailable;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int rows;

    public ChestTrackerScreen() {
        super(Component.literal("ChestTracker"));
    }

    @Override
    protected void init() {
        int gridW = COLS * SLOT;
        panelW = gridW + PADDING * 2;

        int available = height - 80 - TITLE_H - SEARCH_H - FOOTER_H - PADDING * 2;
        rows = Math.max(3, Math.min(9, available / SLOT));
        panelH = TITLE_H + SEARCH_H + rows * SLOT + FOOTER_H + PADDING * 2;

        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        search = new EditBox(font, panelX + PADDING + 1, panelY + PADDING + TITLE_H,
                gridW - 2, SEARCH_H - 2, Component.literal("Search"));
        search.setMaxLength(64);
        search.setHint(Component.literal("Search"));
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
            scrollRow = 0;
            back();
        }));
    }

    private void selectItem(int itemId) {
        selectedItemId = itemId;
        containers = List.of();
        ClientTracker.containersFor(itemId, MAX_CONTAINERS).thenAccept(found -> minecraft.execute(() -> {
            if (selectedItemId == itemId) containers = found;
        }));
    }

    private void back() {
        selectedItemId = -1;
        containers = List.of();
    }

    // --- geometry ----------------------------------------------------------

    private int gridX() {
        return panelX + PADDING;
    }

    private int gridY() {
        return panelY + PADDING + TITLE_H + SEARCH_H;
    }

    private int maxScrollRow() {
        int totalRows = (items.size() + COLS - 1) / COLS;
        return Math.max(0, totalRows - rows);
    }

    /** Index into {@link #items} for a mouse position, or -1. */
    private int slotAt(int mouseX, int mouseY) {
        int col = (mouseX - gridX()) / SLOT;
        int row = (mouseY - gridY()) / SLOT;
        if (mouseX < gridX() || mouseY < gridY() || col < 0 || col >= COLS || row < 0 || row >= rows) return -1;
        int index = (scrollRow + row) * COLS + col;
        return index < items.size() ? index : -1;
    }

    // --- drawing -----------------------------------------------------------

    private void drawPanel(Gfx gfx, int x, int y, int w, int h) {
        gfx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        gfx.fill(x, y, x + w, y + h, PANEL_FILL);
        // Vanilla's raised bevel: light on the top and left, dark on the rest.
        gfx.fill(x, y, x + w, y + 1, BEVEL_LIGHT);
        gfx.fill(x, y, x + 1, y + h, BEVEL_LIGHT);
        gfx.fill(x, y + h - 1, x + w, y + h, BEVEL_DARK);
        gfx.fill(x + w - 1, y, x + w, y + h, BEVEL_DARK);
    }

    private void drawSlot(Gfx gfx, int x, int y) {
        // Inset: the inverse bevel of the panel, which is what reads as a socket.
        gfx.fill(x, y, x + SLOT - 1, y + SLOT - 1, SLOT_DARK);
        gfx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, SLOT_FILL);
        gfx.fill(x + 1, y + SLOT - 2, x + SLOT - 1, y + SLOT - 1, BEVEL_LIGHT);
        gfx.fill(x + SLOT - 2, y + 1, x + SLOT - 1, y + SLOT - 1, BEVEL_LIGHT);
    }

    private void draw(Gfx gfx, int mouseX, int mouseY) {
        drawPanel(gfx, panelX, panelY, panelW, panelH);
        gfx.text(font, Component.literal(selectedItemId < 0 ? "Chest Tracker" : displayName(selectedItemId)),
                panelX + PADDING, panelY + PADDING, TEXT_TITLE);

        if (unavailable) {
            gfx.text(font, Component.literal("No index here yet."), gridX(), gridY(), TEXT_BODY);
            return;
        }

        if (selectedItemId < 0) {
            drawGrid(gfx, mouseX, mouseY);
        } else {
            drawLocations(gfx, mouseX, mouseY);
        }
    }

    private void drawGrid(Gfx gfx, int mouseX, int mouseY) {
        int hovered = slotAt(mouseX, mouseY);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLS; col++) {
                int x = gridX() + col * SLOT;
                int y = gridY() + row * SLOT;
                drawSlot(gfx, x, y);

                int index = (scrollRow + row) * COLS + col;
                if (index >= items.size()) continue;

                WorldIndex.ItemSummary summary = items.get(index);
                gfx.item(iconFor(summary.itemId()), x + 1, y + 1);
                drawCount(gfx, summary.totalCount(), x, y);

                if (index == hovered) {
                    gfx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, SLOT_HOVER);
                }
            }
        }

        int footerY = panelY + panelH - PADDING - FOOTER_H + 3;
        if (hovered >= 0) {
            WorldIndex.ItemSummary summary = items.get(hovered);
            gfx.text(font, Component.literal(String.format("%s  -  %,d in %d",
                            displayName(summary.itemId()), summary.totalCount(), summary.containerCount())),
                    panelX + PADDING, footerY, TEXT_BODY);
        } else if (items.isEmpty()) {
            gfx.text(font, Component.literal(pending.isBlank()
                            ? "Nothing indexed yet"
                            : "No match"),
                    panelX + PADDING, footerY, TEXT_BODY);
        } else {
            gfx.text(font, Component.literal(items.size() + " items"
                            + (maxScrollRow() > 0 ? "  -  scroll" : "")),
                    panelX + PADDING, footerY, TEXT_BODY);
        }
    }

    /** Stack counts sit bottom-right of the slot, light on dark, as in vanilla. */
    private void drawCount(Gfx gfx, int count, int slotX, int slotY) {
        String label = abbreviate(count);
        int textWidth = font.width(label);
        gfx.text(font, label, slotX + SLOT - 2 - textWidth, slotY + SLOT - 9, TEXT_LIGHT);
    }

    private static String abbreviate(int count) {
        if (count < 1000) return Integer.toString(count);
        if (count < 100_000) return String.format("%.1fk", count / 1000.0).replace(".0k", "k");
        return (count / 1000) + "k";
    }

    private void drawLocations(Gfx gfx, int mouseX, int mouseY) {
        int x = gridX();
        int y = gridY();
        int listBottom = gridY() + rows * SLOT;

        if (containers.isEmpty()) {
            gfx.text(font, Component.literal("Looking..."), x, y, TEXT_BODY);
            return;
        }

        for (SearchResult result : containers) {
            if (y + DETAIL_ROW > listBottom) break;
            boolean hovered = mouseX >= x && mouseX <= panelX + panelW - PADDING
                    && mouseY >= y && mouseY < y + DETAIL_ROW;
            if (hovered) gfx.fill(x - 1, y - 1, panelX + panelW - PADDING, y + DETAIL_ROW - 1, ROW_HOVER);
            gfx.text(font, Component.literal(describe(result)), x, y, TEXT_BODY);
            y += DETAIL_ROW;
        }

        gfx.text(font, Component.literal("Click to be guided  -  right-click to go back"),
                panelX + PADDING, panelY + panelH - PADDING - FOOTER_H + 3, TEXT_BODY);
    }

    private String describe(SearchResult result) {
        StringBuilder line = new StringBuilder();
        line.append(result.matchedCount()).append("x ")
                .append(shortName(ClientTracker.nameOf(result.container().typeId())))
                .append("  ").append(BlockKey.toString(result.container().pos()))
                .append(String.format("  %.0fm", result.distance()));
        if (result.matches().stream().anyMatch(entry -> entry.isNested())) line.append(" *");
        if (result.container().origin() == Origin.NATURAL) line.append(" (nat)");
        return line.toString();
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
        if (unavailable) return super.mouseClicked(event, doubleClick);
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        if (event.button() == 1 && selectedItemId >= 0) {
            back();
            return true;
        }

        if (event.button() == 0) {
            if (selectedItemId < 0) {
                int index = slotAt(mouseX, mouseY);
                if (index >= 0) {
                    selectItem(items.get(index).itemId());
                    return true;
                }
            } else if (!containers.isEmpty()) {
                int row = (mouseY - gridY()) / DETAIL_ROW;
                if (row >= 0 && row < containers.size() && mouseX >= gridX()) {
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
        if (selectedItemId < 0) {
            scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow - (int) Math.signum(deltaY)));
        }
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
