package dev.adrian.chesttracker.client.ui;

import dev.adrian.chesttracker.client.ClientTracker;
import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.client.platform.Gfx;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.net.QueryDto;
import dev.adrian.chesttracker.core.util.BlockKey;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * A chest-shaped view of everything you own.
 *
 * <p>Drawn with vanilla's own {@code generic_54} container texture rather than
 * an imitation of it, so the window sits alongside a real chest GUI instead of
 * looking like a mod pasted over the game. Nine slots across and six rows down,
 * a search field, a scrollbar, and a row of filter buttons.
 *
 * <p>Nine across matters: one item per row means scrolling forever once a world
 * has a few hundred distinct items, whereas a grid turns that into a glance.
 *
 * <p>Two views share the window - the item grid, and the containers holding a
 * chosen item. Clicking a location closes the screen and hands over to guidance,
 * because the player is about to walk there.
 */
public final class ChestTrackerScreen extends Screen {

    private static final Identifier CONTAINER_TEXTURE =
            Identifier.parse("minecraft:textures/gui/container/generic_54.png");

    // Geometry of generic_54.png: a 176x222 window on a 256x256 sheet.
    private static final int SHEET = 256;
    private static final int GUI_W = 176;
    private static final int TOP_H = 17;          // top border and title row
    private static final int ROWS_V = 17;         // where the six slot rows start
    private static final int ROWS_H = 108;        // six rows of 18px
    private static final int BOTTOM_V = 215;      // bottom border in the sheet
    private static final int BOTTOM_H = 7;
    private static final int SLOTS_W = 169;       // left border plus nine slots
    private static final int SCROLL_COL = 14;     // widened for the scrollbar
    private static final int EDGE_W = 7;          // the window's side border
    private static final int FILLER_U = 100;      // a uniform column of the border bands
    private static final int SLOT_FILLER_U = 170; // flat panel between slots and edge

    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int SLOT = 18;
    private static final int SEARCH_H = 16;

    // Vanilla's container palette, for the parts drawn rather than blitted.
    private static final int BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int BEVEL_DARK = 0xFF555555;
    private static final int GROOVE = 0xFF8B8B8B;
    private static final int GROOVE_DARK = 0xFF373737;
    /**
     * Standard Minecraft text: white, with the drop shadow the font draws by
     * default.
     *
     * <p>Vanilla's container labels are dark grey and shadowless, and copying
     * that looked muddy here - the shadow is on by default in the draw call, so
     * dark grey text was being drawn with a dark outline behind it.
     *
     * <p>Alpha is spelled out. {@code 0xFFFFFF} is fully transparent in ARGB
     * and draws nothing at all; that bug has shipped here once already.
     */
    private static final int TEXT_MAIN = 0xFFFFFFFF;

    /** Standard secondary text, for a filter sitting at its default. */
    private static final int TEXT_MUTED = 0xFFAAAAAA;

    /** Icons drawn as shapes rather than glyphs need to contrast with the panel. */
    private static final int ICON = 0xFF404040;
    private static final int TEXT_LIGHT = 0xFFFFFFFF;
    private static final int SLOT_HOVER = 0x80FFFFFF;
    private static final int ROW_HOVER = 0x40000000;
    private static final int BUTTON_ON = 0xFF6A9A4A;

    private static final int BUTTON_SIZE = 12;
    private static final int TOOLBAR_BUTTONS = 2;   // menu, close
    private static final int MENU_W = 132;
    private static final int MENU_ROW_H = 12;

    /**
     * Floor between automatic refreshes.
     *
     * <p>The server already limits how often it reports a change, but a
     * singleplayer world reads the counter directly and has no such limit -
     * a hopper line would otherwise re-query every frame.
     */
    private static final long AUTO_REFRESH_MIN_MS = 400;

    /** How often a refused screen re-asks, in case permission changed. */
    private static final long REFUSED_RETRY_MS = 2000;

    private static final int MAX_CONTAINERS = 64;
    private static final int DETAIL_ROW = 11;

    /** Breathing room between the list well's bevel and its first row. */
    private static final int LIST_PAD = 3;

    /** Registry lookups are not free and slots redraw every frame. */
    private static final Map<String, ItemStack> ICON_CACHE = new HashMap<>();

    private enum Sort {
        COUNT("Most"), NEAREST("Nearest"), NAME("A-Z");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        static Sort parse(String name) {
            for (Sort value : values()) {
                if (value.name().equalsIgnoreCase(name)) return value;
            }
            return COUNT;
        }
    }

    private EditBox search;
    private String pending = ChestTrackerConfig.get().searchText;

    private List<QueryDto.ItemSummary> items = List.of();
    private List<QueryDto.ContainerHit> containers = List.of();
    private String selectedItemId;
    private int scrollRow;

    /**
     * First visible row of the container list, which scrolls separately.
     *
     * <p>Sharing the grid's offset would scroll one view by opening the other,
     * and the two count in different units - rows of nine against single rows.
     */
    private int detailScroll;

    private boolean draggingScrollbar;
    private boolean menuOpen;

    private ClientTracker.Availability availability = ClientTracker.Availability.NONE;

    /**
     * Ids of the newest replies accepted, per view.
     *
     * <p>Every keystroke starts a query and replies need not come back in the
     * order they were asked for. Ids only ever increase, so anything not newer
     * than what is already shown is a straggler and is dropped.
     */
    private int newestItemsReply;
    private int newestContainersReply;

    /**
     * Whether the detail pane is still waiting.
     *
     * <p>An empty list is a real answer - the filters excluded everything, or a
     * remote query timed out - so it cannot be told apart from "not back yet"
     * without saying so. Before this, both showed "Looking..." and one of them
     * never stopped.
     */
    private boolean containersPending;

    /**
     * The index generation this screen last drew, and when it last re-asked.
     *
     * <p>Comparing a token beats being called back: a change that arrives while
     * no screen is open costs nothing, and a closed screen cannot leave a
     * listener behind.
     */
    private long lastChangeToken;
    private long lastAutoRefresh;

    /** A change arrived while the detail pane was open, so the grid is behind. */
    private boolean itemsStale;

    // Every filter picks up where it was left. Re-choosing them on each open
    // is the kind of friction that makes a tool feel unfinished.
    private Sort sort = Sort.parse(ChestTrackerConfig.get().sortMode);
    private boolean includeNested = ChestTrackerConfig.get().includeNested;
    private boolean includeMachines = ChestTrackerConfig.get().showMachines;
    private int originIndex = Math.max(0, Math.min(2, ChestTrackerConfig.get().originFilter));

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private String hoverLabel;

    public ChestTrackerScreen() {
        super(Component.literal("Chest Tracker"));
    }

    @Override
    protected void init() {
        panelW = GUI_W + SCROLL_COL;
        panelH = TOP_H + SEARCH_H + ROWS_H + BOTTOM_H;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        search = new EditBox(font, panelX + 8, panelY + TOP_H + 3, SLOTS_W - 10, 12,
                Component.literal("Search"));
        search.setMaxLength(64);
        search.setHint(Component.literal("Search"));
        // Set before the responder, so restoring the last search does not count
        // as the player typing it again.
        search.setValue(pending);
        search.setResponder(value -> {
            pending = value;
            refreshItems();
        });
        addRenderableWidget(search);
        setInitialFocus(search);

        availability = ClientTracker.availability();
        lastChangeToken = ClientTracker.changeToken();
        // Ask the server to push changes only while this is on screen.
        ClientTracker.setWatching(true);
        refreshItems();
    }

    @Override
    public void removed() {
        ClientTracker.setWatching(false);
        rememberPreferences();
        super.removed();
    }

    /**
     * Writes the filters and the search box back to the settings.
     *
     * <p>Only when something actually moved, so closing the screen does not
     * rewrite the config file every time it is opened and shut.
     */
    private void rememberPreferences() {
        ChestTrackerConfig config = ChestTrackerConfig.get();
        boolean changed = config.includeNested != includeNested
                || config.showMachines != includeMachines
                || config.originFilter != originIndex
                || !config.sortMode.equals(sort.name())
                || !config.searchText.equals(pending);
        if (!changed) return;

        config.includeNested = includeNested;
        config.showMachines = includeMachines;
        config.originFilter = originIndex;
        config.sortMode = sort.name();
        config.searchText = pending;
        config.save();
    }

    // --- data --------------------------------------------------------------

    private QueryDto.Filters filters() {
        return new QueryDto.Filters(includeNested, includeMachines, originIndex);
    }

    /** A refresh the player asked for: back to the grid, scrolled to the top. */
    private void refreshItems() {
        refreshItems(true);
    }

    /**
     * @param resetView true when the player changed the search or the filters,
     *                  false for a background refresh - which must leave the
     *                  scroll position and the open pane alone, or the grid
     *                  jumps under the cursor every time a hopper moves an item
     */
    private void refreshItems(boolean resetView) {
        if (!mayAttempt()) return;
        ClientTracker.summarise(pending, filters(), ChestTrackerConfig.get().resultLimit()).thenAccept(response ->
                minecraft.execute(() -> {
                    // A slow earlier query must not overwrite a newer one's results.
                    if (response.requestId() <= newestItemsReply) return;
                    newestItemsReply = response.requestId();
                    items = sorted(response.items());
                    itemsStale = false;
                    if (!resetView) {
                        // The list can shrink, so a kept scroll can end up past
                        // the end of it.
                        scrollRow = Math.min(scrollRow, maxScrollRow());
                        return;
                    }
                    scrollRow = 0;
                    back();
                }));
    }

    /**
     * Re-asks where the selected item is, without clearing what is on screen.
     *
     * <p>Used for background refreshes, so a live update does not blink the
     * pane through "Looking..." on every change.
     */
    private void refreshContainers() {
        String itemId = selectedItemId;
        if (itemId == null || !mayAttempt()) return;
        ClientTracker.containers(itemId, filters(), MAX_CONTAINERS).thenAccept(response ->
                minecraft.execute(() -> {
                    if (!itemId.equals(selectedItemId)) return;
                    if (response.requestId() <= newestContainersReply) return;
                    newestContainersReply = response.requestId();
                    containers = response.hits();
                    containersPending = false;
                    // A background refresh can shorten the list under a kept
                    // scroll position - a container was emptied or broken.
                    detailScroll = Math.min(detailScroll, maxDetailScroll());
                }));
    }

    /**
     * Keeps asking while refused, so a permission change lands on its own.
     *
     * <p>Much slower than a normal refresh: nothing is being shown, and the
     * only thing that can change is an answer the player is waiting on rather
     * than watching.
     */
    private void retryWhileRefused() {
        if (availability != ClientTracker.Availability.NOT_PERMITTED) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoRefresh < REFUSED_RETRY_MS) return;
        lastAutoRefresh = now;
        refreshItems(false);
    }

    /**
     * Re-asks whichever view is open, when the index has moved under it.
     *
     * <p>Throttled, and only when the token actually changed - the token is not
     * consumed while throttled, so a change during the quiet period is picked
     * up as soon as it ends rather than lost.
     */
    private void pollForChanges() {
        long token = ClientTracker.changeToken();
        if (token == lastChangeToken) return;

        long now = System.currentTimeMillis();
        if (now - lastAutoRefresh < AUTO_REFRESH_MIN_MS) return;
        lastChangeToken = token;
        lastAutoRefresh = now;

        if (selectedItemId == null) {
            refreshItems(false);
        } else {
            // Refreshing the grid as well would double every update's cost for
            // a pane the player cannot see. It is marked instead, and caught up
            // when they go back to it.
            refreshContainers();
            itemsStale = true;
        }
    }

    /** Whether there is anything to show right now. */
    private boolean canQuery() {
        return availability == ClientTracker.Availability.LOCAL
                || availability == ClientTracker.Availability.SERVER;
    }

    /**
     * Whether it is worth asking at all.
     *
     * <p>Deliberately wider than {@link #canQuery()}: a refused player keeps
     * asking, because the reply is what carries the current answer. Being opped
     * mid-session would otherwise never be noticed - the screen would refuse to
     * ask precisely because of the stale answer it was trying to replace.
     */
    private boolean mayAttempt() {
        return canQuery() || availability == ClientTracker.Availability.NOT_PERMITTED;
    }

    private List<QueryDto.ItemSummary> sorted(List<QueryDto.ItemSummary> source) {
        List<QueryDto.ItemSummary> copy = new ArrayList<>(source);
        switch (sort) {
            case NEAREST -> copy.sort(Comparator.comparingDouble(QueryDto.ItemSummary::nearestDistSq));
            case NAME -> copy.sort(Comparator.comparing(entry -> displayName(entry.itemId())));
            case COUNT -> { /* summarise already returns most-plentiful first */ }
        }
        return copy;
    }

    /**
     * Closes the screen and outlines every container holding the item.
     *
     * <p>Closes first and highlights when the answer arrives, rather than
     * waiting: the player has said where they want to go, and holding the
     * window open over the world while a server replies reads as a stall.
     */
    private void highlightItem(String itemId) {
        if (!mayAttempt()) return;
        String label = displayName(itemId);
        String dimensionId = minecraft.player.level().dimension().identifier().toString();

        ClientTracker.containers(itemId, filters(), MAX_CONTAINERS).thenAccept(response ->
                minecraft.execute(() -> {
                    if (response.hits().isEmpty()) {
                        ClientCompat.actionBar(Component.literal("Nothing indexed holds " + label));
                        return;
                    }
                    // Already nearest-first: the server ranks by distance, and
                    // the highlight takes the first as the one to guide to.
                    List<Long> positions = new ArrayList<>(response.hits().size());
                    for (QueryDto.ContainerHit hit : response.hits()) positions.add(hit.pos());
                    ContainerHighlight.get().select(positions, dimensionId, label);
                }));
        onClose();
    }

    private void selectItem(String itemId) {
        selectedItemId = itemId;
        containers = List.of();
        containersPending = true;
        detailScroll = 0;
        refreshContainers();
    }

    private void back() {
        selectedItemId = null;
        containers = List.of();
        containersPending = false;
    }

    // --- geometry ----------------------------------------------------------

    private int gridX() {
        return panelX + 8;
    }

    private int gridY() {
        return panelY + TOP_H + SEARCH_H + 1;
    }

    private int scrollbarX() {
        return panelX + SLOTS_W + 3;
    }

    private int gridRows() {
        return (items.size() + COLS - 1) / COLS;
    }

    private int maxScrollRow() {
        return Math.max(0, gridRows() - ROWS);
    }

    // The container list occupies the rectangle the slot art is blitted into,
    // taken from where that art actually starts rather than from the grid's
    // inner origin, so the list's bevel lands on the window's own border.

    private int listLeft() {
        return panelX + EDGE_W;
    }

    private int listTop() {
        return panelY + TOP_H + SEARCH_H;
    }

    private int listWidth() {
        return COLS * SLOT;
    }

    private int listHeight() {
        return ROWS * SLOT;
    }

    /** Rows that fit inside the well. Anything past this has to be scrolled to. */
    private int listRowsVisible() {
        return (listHeight() - LIST_PAD * 2) / DETAIL_ROW;
    }

    private int maxDetailScroll() {
        return Math.max(0, containers.size() - listRowsVisible());
    }

    private int listRowY(int row) {
        return listTop() + LIST_PAD + row * DETAIL_ROW;
    }

    /**
     * The container under the cursor, or -1.
     *
     * <p>One method for both the highlight and the click, so what lights up is
     * always what gets selected. Deciding it twice is how a click lands on the
     * row above the one being pointed at - and the old click test bounded
     * neither the top of the pane nor its right edge, so the empty strip above
     * the list guided the player to its first row.
     */
    private int rowAt(int mouseX, int mouseY) {
        if (mouseX < listLeft() || mouseX >= listLeft() + listWidth()) return -1;
        int offset = mouseY - listRowY(0);
        if (offset < 0) return -1;
        int row = offset / DETAIL_ROW;
        if (row >= listRowsVisible()) return -1;
        int index = detailScroll + row;
        return index < containers.size() ? index : -1;
    }

    private int slotAt(int mouseX, int mouseY) {
        int col = (mouseX - gridX()) / SLOT;
        int row = (mouseY - gridY()) / SLOT;
        if (mouseX < gridX() || mouseY < gridY() || col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        int index = (scrollRow + row) * COLS + col;
        return index < items.size() ? index : -1;
    }

    // --- drawing -----------------------------------------------------------

    /**
     * Everything drawn on top of the widgets.
     *
     * <p>The window itself is deliberately not drawn here. Widgets render
     * between the background and this, so painting the panel at this point put
     * it over the search field and hid whatever was being typed.
     */
    private void draw(Gfx gfx, int mouseX, int mouseY) {
        hoverLabel = null;
        drawButtons(gfx, mouseX, mouseY);

        // The server announces itself shortly after joining, so a screen opened
        // during that window has to notice when the answer arrives.
        ClientTracker.Availability now = ClientTracker.availability();
        if (now != availability) {
            availability = now;
            // A server that has just announced itself may not have been asked
            // to push yet.
            ClientTracker.setWatching(true);
            lastChangeToken = ClientTracker.changeToken();
            refreshItems();
        } else {
            pollForChanges();
            retryWhileRefused();
        }

        if (!canQuery()) {
            gfx.text(font, Component.literal(unavailableMessage()), gridX(), gridY() + 4, TEXT_MAIN);
            return;
        }

        if (selectedItemId == null) {
            drawGrid(gfx, mouseX, mouseY);
            drawScrollbar(gfx, scrollRow, maxScrollRow(), gridRows(), ROWS);
        } else {
            drawLocations(gfx, mouseX, mouseY);
            drawScrollbar(gfx, detailScroll, maxDetailScroll(),
                    containers.size(), listRowsVisible());
        }

        String title = hoverLabel != null ? hoverLabel
                : selectedItemId != null ? displayName(selectedItemId) : "Chest Tracker";
        // The toolbar occupies the right of the title row, so the text is
        // clipped to what is left rather than running underneath it.
        int available = buttonX(0) - (panelX + 8) - 4;
        gfx.text(font, Component.literal(truncate(title, available)), panelX + 8, panelY + 6, TEXT_MAIN);

        drawMenu(gfx, mouseX, mouseY);
    }

    /**
     * Why there is nothing to show.
     *
     * <p>Worth distinguishing: a vanilla server, a server that will not answer
     * this player, and a world that simply has not been scanned are three
     * different problems, and one message for all of them sends people looking
     * in the wrong place.
     */
    private String unavailableMessage() {
        return switch (availability) {
            case CONNECTING -> "Asking the server...";
            case NOT_PERMITTED -> "This server does not allow searching.";
            default -> "No index here yet.";
        };
    }

    /**
     * Blits vanilla's chest window, widened by a scrollbar column.
     *
     * <p>The top and bottom borders are uniform horizontal bands, so a second
     * right-aligned copy overlaps the first seamlessly. The slot rows are not,
     * so those are drawn as left-plus-slots, a flat filler, and the right border.
     */
    private void drawWindow(Gfx gfx) {
        int searchY = panelY + TOP_H;
        int gridTop = searchY + SEARCH_H;
        int bottomY = gridTop + ROWS_H;

        drawBand(gfx, 0, TOP_H, panelY);
        drawSearchStrip(gfx, searchY);
        drawSlotBand(gfx, gridTop);
        drawBand(gfx, BOTTOM_V, BOTTOM_H, bottomY);
    }

    /**
     * Draws one horizontal band of the window, widened for the scrollbar column.
     *
     * <p>The band cannot simply be blitted twice, once right-aligned: its ends
     * are corner art, so the second copy paints a corner into the middle of the
     * panel. Instead the left part is drawn, the uniform middle is repeated a
     * pixel at a time to bridge the extra width, and the right edge is drawn
     * last so the border lands where the panel actually ends.
     */
    private void drawBand(Gfx gfx, int srcV, int height, int destY) {
        gfx.blit(CONTAINER_TEXTURE, panelX, destY, 0, srcV, SLOTS_W, height, SHEET, SHEET);
        for (int i = 0; i < SCROLL_COL; i++) {
            gfx.blit(CONTAINER_TEXTURE, panelX + SLOTS_W + i, destY, FILLER_U, srcV, 1, height, SHEET, SHEET);
        }
        gfx.blit(CONTAINER_TEXTURE, panelX + panelW - EDGE_W, destY,
                GUI_W - EDGE_W, srcV, EDGE_W, height, SHEET, SHEET);
    }

    /**
     * The slot rows. The gap left by widening for the scrollbar is filled from
     * the flat strip between the last slot and the window edge, repeated a pixel
     * at a time - not with a fixed colour. A resource pack repaints this texture,
     * and a hardcoded grey would sit in the middle of someone's brown chest.
     */
    private void drawSlotBand(Gfx gfx, int destY) {
        gfx.blit(CONTAINER_TEXTURE, panelX, destY, 0, ROWS_V, SLOTS_W, ROWS_H, SHEET, SHEET);
        for (int i = 0; i < SCROLL_COL; i++) {
            gfx.blit(CONTAINER_TEXTURE, panelX + SLOTS_W + i, destY,
                    SLOT_FILLER_U, ROWS_V, 1, ROWS_H, SHEET, SHEET);
        }
        gfx.blit(CONTAINER_TEXTURE, panelX + panelW - EDGE_W, destY,
                GUI_W - EDGE_W, ROWS_V, EDGE_W, ROWS_H, SHEET, SHEET);
    }

    /** The search strip, also built from the texture so packs carry through. */
    private void drawSearchStrip(Gfx gfx, int searchY) {
        gfx.blit(CONTAINER_TEXTURE, panelX, searchY, 0, 30, EDGE_W, SEARCH_H, SHEET, SHEET);
        for (int x = panelX + EDGE_W; x < panelX + panelW - EDGE_W; x++) {
            gfx.blit(CONTAINER_TEXTURE, x, searchY, SLOT_FILLER_U, 30, 1, SEARCH_H, SHEET, SHEET);
        }
        gfx.blit(CONTAINER_TEXTURE, panelX + panelW - EDGE_W, searchY,
                GUI_W - EDGE_W, 30, EDGE_W, SEARCH_H, SHEET, SHEET);
        drawGroove(gfx, panelX + 7, searchY + 2, panelW - 14, 12);
    }

    /**
     * Fills a rectangle with the window's own flat panel pixels.
     *
     * <p>Used instead of a fixed colour for the parts vanilla has no art for -
     * the scrollbar thumb and the toolbar buttons - so they follow a resource
     * pack rather than sitting in it as grey rectangles.
     */
    private void fillFromTexture(Gfx gfx, int x, int y, int w, int h) {
        for (int i = 0; i < w; i++) {
            gfx.blit(CONTAINER_TEXTURE, x + i, y, SLOT_FILLER_U, ROWS_V, 1, h, SHEET, SHEET);
        }
    }

    /**
     * A sunken panel with the window's own pixels inside it.
     *
     * <p>Like {@link #drawGroove} but without the flat grey fill, so a resource
     * pack's chest shows through the middle of the list rather than a vanilla
     * rectangle sitting in it.
     */
    private void drawWell(Gfx gfx, int x, int y, int w, int h) {
        fillFromTexture(gfx, x, y, w, h);
        gfx.fill(x, y, x + w - 1, y + 1, GROOVE_DARK);
        gfx.fill(x, y, x + 1, y + h - 1, GROOVE_DARK);
        gfx.fill(x + w - 1, y, x + w, y + h, BEVEL_LIGHT);
        gfx.fill(x, y + h - 1, x + w, y + h, BEVEL_LIGHT);
    }

    /** The inset used by vanilla for slots and text fields. */
    private void drawGroove(Gfx gfx, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, GROOVE_DARK);
        gfx.fill(x + 1, y + 1, x + w, y + h, BEVEL_LIGHT);
        gfx.fill(x + 1, y + 1, x + w - 1, y + h - 1, GROOVE);
    }

    private void drawGrid(Gfx gfx, int mouseX, int mouseY) {
        int hovered = slotAt(mouseX, mouseY);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (scrollRow + row) * COLS + col;
                if (index >= items.size()) continue;

                int x = gridX() + col * SLOT;
                int y = gridY() + row * SLOT;
                QueryDto.ItemSummary summary = items.get(index);

                gfx.item(iconFor(summary.itemId()), x, y);
                drawCount(gfx, summary.totalCount(), x, y);
                if (index == hovered) gfx.fill(x, y, x + 16, y + 16, SLOT_HOVER);
            }
        }

        if (hovered >= 0) {
            QueryDto.ItemSummary summary = items.get(hovered);
            hoverLabel = String.format("%s  %,d in %d  -  right-click to list",
                    displayName(summary.itemId()), summary.totalCount(), summary.containerCount());
        } else if (items.isEmpty()) {
            gfx.text(font, Component.literal(pending.isBlank() ? "Nothing indexed yet" : "No match"),
                    gridX(), gridY() + 4, TEXT_MAIN);
        }
    }

    /**
     * Stack counts sit bottom-right of the slot, light on dark, as in vanilla.
     *
     * <p>Labels are kept to three characters. A slot is 18px and the font is
     * not scaled here, so "3.1k" is wider than the slot and bleeds into its
     * neighbour - which is exactly what it looked like.
     */
    private void drawCount(Gfx gfx, int count, int slotX, int slotY) {
        if (count <= 1) return; // Vanilla omits a count of one.
        String label = abbreviate(count);
        gfx.text(font, label, slotX + 17 - font.width(label), slotY + 9, TEXT_LIGHT);
    }

    private static String abbreviate(int count) {
        if (count < 1000) return Integer.toString(count);
        if (count < 1_000_000) return (count / 1000) + "k";
        return (count / 1_000_000) + "m";
    }

    /**
     * The scrollbar for whichever view is open.
     *
     * <p>Both views scroll, so the bar is told what it is scrolling rather than
     * reading the grid's numbers - a thumb sized off the item count while the
     * container list was showing would describe the wrong list.
     */
    private void drawScrollbar(Gfx gfx, int scroll, int maxScroll, int totalRows, int visibleRows) {
        int x = scrollbarX();
        int y = gridY() - 1;
        int height = ROWS * SLOT;
        drawGroove(gfx, x, y, 12, height);

        int thumbHeight = maxScroll == 0 ? height - 2
                : Math.max(15, (height - 2) * visibleRows / Math.max(1, totalRows));
        int travel = height - 2 - thumbHeight;
        int thumbY = y + 1 + (maxScroll == 0 ? 0 : travel * scroll / maxScroll);

        fillFromTexture(gfx, x + 1, thumbY, 10, thumbHeight);
        gfx.fill(x + 1, thumbY, x + 11, thumbY + 1, BEVEL_LIGHT);
        gfx.fill(x + 1, thumbY, x + 2, thumbY + thumbHeight, BEVEL_LIGHT);
        gfx.fill(x + 1, thumbY + thumbHeight - 1, x + 11, thumbY + thumbHeight, BEVEL_DARK);
        gfx.fill(x + 10, thumbY, x + 11, thumbY + thumbHeight, BEVEL_DARK);
    }

    /**
     * One line of the menu: what it controls, and where it currently stands.
     *
     * <p>These used to be four single-letter buttons that cycled on click. The
     * letters said nothing about what they did or what state they were in, so
     * the only way to find out was to click one and watch the results change -
     * and hoppers being hidden by default was undiscoverable that way.
     */
    private record MenuRow(String label, String value, boolean active) {}

    private List<MenuRow> menuRows() {
        String origin = switch (originIndex) {
            // "built" rather than "player-placed": it includes containers whose
            // placement was never observed, which on any world older than the
            // mod is most of them.
            case 1 -> "built";
            case 2 -> "generated";
            default -> "any";
        };
        return List.of(
                new MenuRow("Sort by", sort.label, sort != Sort.COUNT),
                new MenuRow("Origin", origin, originIndex != 0),
                new MenuRow("Inside shulkers", includeNested ? "counted" : "ignored", includeNested),
                new MenuRow("Machines", includeMachines ? "shown" : "hidden", includeMachines));
    }

    private int buttonX(int index) {
        return panelX + panelW - 8 - (TOOLBAR_BUTTONS - index) * (BUTTON_SIZE + 1);
    }

    private int menuX() {
        return panelX + panelW - 8 - MENU_W;
    }

    private int menuY() {
        return panelY + 3 + BUTTON_SIZE + 1;
    }

    private int menuH() {
        return menuRows().size() * MENU_ROW_H + 3;
    }

    private void drawButtons(Gfx gfx, int mouseX, int mouseY) {
        for (int i = 0; i < TOOLBAR_BUTTONS; i++) {
            int x = buttonX(i);
            int y = panelY + 3;
            boolean hovered = mouseX >= x && mouseX < x + BUTTON_SIZE
                    && mouseY >= y && mouseY < y + BUTTON_SIZE;
            boolean active = i == 0 && menuOpen;

            gfx.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, BEVEL_DARK);
            if (active) {
                gfx.fill(x, y, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, BUTTON_ON);
            } else {
                fillFromTexture(gfx, x, y, BUTTON_SIZE - 1, BUTTON_SIZE - 1);
            }
            gfx.fill(x, y, x + BUTTON_SIZE - 1, y + 1, BEVEL_LIGHT);
            gfx.fill(x, y, x + 1, y + BUTTON_SIZE - 1, BEVEL_LIGHT);
            if (hovered) {
                gfx.fill(x + 1, y + 1, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, SLOT_HOVER);
                hoverLabel = i == 0 ? "Filters and sorting" : "Close";
            }

            if (i == 0) {
                // Drawn rather than typed: the font has no glyph that reads as
                // a menu, and a letter would be back where we started.
                for (int line = 0; line < 3; line++) {
                    gfx.fill(x + 3, y + 3 + line * 3, x + BUTTON_SIZE - 3, y + 4 + line * 3, ICON);
                }
            } else {
                gfx.text(font, "X", x + 3, y + 2, TEXT_MAIN);
            }
        }
    }

    /**
     * The dropdown, drawn last so it sits over the grid rather than under it.
     */
    private void drawMenu(Gfx gfx, int mouseX, int mouseY) {
        if (!menuOpen) return;

        int x = menuX();
        int y = menuY();
        int height = menuH();

        gfx.fill(x - 1, y - 1, x + MENU_W + 1, y + height + 1, BEVEL_DARK);
        fillFromTexture(gfx, x, y, MENU_W, height);
        gfx.fill(x, y, x + MENU_W, y + 1, BEVEL_LIGHT);
        gfx.fill(x, y, x + 1, y + height, BEVEL_LIGHT);

        List<MenuRow> rows = menuRows();
        for (int i = 0; i < rows.size(); i++) {
            MenuRow row = rows.get(i);
            int rowY = y + 2 + i * MENU_ROW_H;
            if (mouseX >= x && mouseX < x + MENU_W && mouseY >= rowY - 1 && mouseY < rowY + MENU_ROW_H - 1) {
                gfx.fill(x + 1, rowY - 1, x + MENU_W, rowY + MENU_ROW_H - 1, SLOT_HOVER);
            }
            gfx.text(font, Component.literal(row.label()), x + 4, rowY, TEXT_MAIN);
            // The value is right-aligned so the states line up in a column and
            // can be read down without reading every label.
            String value = row.value();
            // The one place a colour still means something: a filter that has
            // been moved off its default reads at full strength, one left alone
            // is greyed. Both are vanilla greys.
            gfx.text(font, Component.literal(value), x + MENU_W - 4 - font.width(value), rowY,
                    row.active() ? TEXT_MAIN : TEXT_MUTED);
        }
    }

    /**
     * The containers holding the chosen item, one per row.
     *
     * <p>The rows get a well of their own rather than being written across the
     * slot grid the window always draws. Nine bevelled squares behind every
     * line of text is what made this pane unreadable: the grid's white edges
     * struck through the glyphs, and its columns read as table rules that were
     * not there.
     */
    private void drawLocations(Gfx gfx, int mouseX, int mouseY) {
        drawWell(gfx, listLeft(), listTop(), listWidth(), listHeight());

        int textX = listLeft() + 4;
        int textWidth = listWidth() - 8;

        if (containers.isEmpty()) {
            gfx.text(font, Component.literal(containersPending ? "Looking..." : "Nothing holds that."),
                    textX, listRowY(0), TEXT_MAIN);
            return;
        }

        int hovered = rowAt(mouseX, mouseY);
        int last = Math.min(containers.size(), detailScroll + listRowsVisible());
        for (int index = detailScroll; index < last; index++) {
            QueryDto.ContainerHit hit = containers.get(index);
            int y = listRowY(index - detailScroll);
            if (index == hovered) {
                gfx.fill(listLeft() + 1, y - 1, listLeft() + listWidth() - 1,
                        y + DETAIL_ROW - 1, ROW_HOVER);
            }

            // Two columns rather than one string: at 162 pixels a row of four
            // facts does not fit any world with five-digit coordinates, and
            // trimming one string drops whatever is last. The distance is the
            // field a player is choosing on, so it is anchored to the right and
            // the description gives way first.
            String distance = distanceOf(hit);
            int distanceWidth = font.width(distance);
            gfx.text(font, Component.literal(distance),
                    listLeft() + listWidth() - 4 - distanceWidth, y, TEXT_MAIN);
            gfx.text(font, Component.literal(
                            truncate(describe(hit), textWidth - distanceWidth - 4)),
                    textX, y, TEXT_MAIN);
        }
        hoverLabel = "Click to be guided  -  right-click to go back";
    }

    /**
     * A row's left column: how much, in what, and how sure we are of it.
     *
     * <p>The marks come before the position rather than after it, so that the
     * one part of the row that can be trimmed is the part a player does not
     * need to read - guidance walks them there, the coordinates are a courtesy.
     */
    private String describe(QueryDto.ContainerHit hit) {
        StringBuilder line = new StringBuilder();
        line.append(hit.matchedCount()).append("x ").append(shortName(hit.typeId()));
        if (hit.nested()) line.append(" *");
        // A container we have only ever seen from the outside must not read as
        // one we have counted - "?" is honest, an unmarked row is not.
        if (!hit.contentsKnown()) line.append(" ?");
        line.append("  ").append(BlockKey.toString(hit.pos()));
        return line.toString();
    }

    /** A row's right column. */
    private static String distanceOf(QueryDto.ContainerHit hit) {
        return String.format("%.0fm", Math.sqrt(hit.distanceSq()));
    }

    // --- item display ------------------------------------------------------

    private static ItemStack iconFor(String itemId) {
        return ICON_CACHE.computeIfAbsent(itemId, id -> {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier == null) return ItemStack.EMPTY;
            Item item = BuiltInRegistries.ITEM.getValue(identifier);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    /**
     * The item's translated name, falling back to its registry path.
     *
     * <p>A server may index an item this client does not have - a mod present
     * only on the server - so the registry lookup genuinely can miss, and the
     * row still has to say something useful.
     */
    private static String displayName(String itemId) {
        ItemStack stack = iconFor(itemId);
        return stack.isEmpty() ? shortName(itemId) : stack.getHoverName().getString();
    }

    /** Trims text to a pixel width, with an ellipsis when it does not fit. */
    private String truncate(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        StringBuilder trimmed = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(trimmed.toString() + c + "...") > maxWidth) break;
            trimmed.append(c);
        }
        return trimmed + "...";
    }

    private static String shortName(String registryId) {
        int colon = registryId.indexOf(':');
        return colon < 0 ? registryId : registryId.substring(colon + 1);
    }

    // --- input -------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        // The menu overlays the grid, so it gets first refusal on a click.
        if (event.button() == 0 && menuOpen) {
            if (clickMenuRow(mouseX, mouseY)) return true;
            if (!overToolbar(mouseX, mouseY)) {
                // Anywhere else dismisses it, without also acting on whatever
                // was underneath - which would be a click the player never
                // meant to make on the grid.
                menuOpen = false;
                return true;
            }
        }

        if (event.button() == 0 && clickButton(mouseX, mouseY)) return true;
        if (!canQuery()) return super.mouseClicked(event, doubleClick);

        if (event.button() == 1) {
            if (selectedItemId != null) {
                back();
                // Changes that arrived while the pane was open were only applied
                // to the pane; the grid behind it is caught up here.
                if (itemsStale) refreshItems(false);
                return true;
            }
            // Right-click opens the list of places. Left-click is the common
            // case - point me at this - and the list is the answer to a
            // narrower question, so it is the one behind the second button.
            int index = slotAt(mouseX, mouseY);
            if (index >= 0) {
                selectItem(items.get(index).itemId());
                return true;
            }
        }

        if (event.button() == 0) {
            // The bar serves both views, so it is tested before either of them.
            if (mouseX >= scrollbarX() && mouseX < scrollbarX() + 12
                    && mouseY >= gridY() - 1 && mouseY < gridY() - 1 + ROWS * SLOT) {
                draggingScrollbar = true;
                dragScrollbar(mouseY);
                return true;
            }
            if (selectedItemId == null) {
                int index = slotAt(mouseX, mouseY);
                if (index >= 0) {
                    highlightItem(items.get(index).itemId());
                    return true;
                }
            } else {
                int index = rowAt(mouseX, mouseY);
                if (index >= 0) {
                    guideTo(containers.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean overToolbar(int mouseX, int mouseY) {
        int y = panelY + 3;
        if (mouseY < y || mouseY >= y + BUTTON_SIZE) return false;
        return mouseX >= buttonX(0) && mouseX < buttonX(TOOLBAR_BUTTONS - 1) + BUTTON_SIZE;
    }

    private boolean clickButton(int mouseX, int mouseY) {
        int y = panelY + 3;
        if (mouseY < y || mouseY >= y + BUTTON_SIZE) return false;

        for (int i = 0; i < TOOLBAR_BUTTONS; i++) {
            int x = buttonX(i);
            if (mouseX < x || mouseX >= x + BUTTON_SIZE) continue;
            if (i == 0) {
                menuOpen = !menuOpen;
            } else {
                onClose();
            }
            return true;
        }
        return false;
    }

    /**
     * Applies a click on one menu row.
     *
     * <p>The menu stays open: these settings are usually adjusted together, and
     * reopening it after each one would be four clicks where one is meant.
     */
    private boolean clickMenuRow(int mouseX, int mouseY) {
        int x = menuX();
        int y = menuY();
        if (mouseX < x || mouseX >= x + MENU_W || mouseY < y || mouseY >= y + menuH()) return false;

        int row = (mouseY - (y + 1)) / MENU_ROW_H;
        switch (row) {
            case 0 -> {
                // Sorting is done on what we already have, so it needs no query.
                sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
                items = sorted(items);
                scrollRow = 0;
            }
            case 1 -> {
                originIndex = (originIndex + 1) % 3;
                refreshItems();
            }
            case 2 -> {
                includeNested = !includeNested;
                refreshItems();
            }
            case 3 -> {
                includeMachines = !includeMachines;
                refreshItems();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void dragScrollbar(int mouseY) {
        int height = ROWS * SLOT;
        boolean grid = selectedItemId == null;
        int maxScroll = grid ? maxScrollRow() : maxDetailScroll();
        if (maxScroll == 0) return;
        double fraction = (mouseY - (gridY() - 1)) / (double) height;
        int row = Math.max(0, Math.min(maxScroll, (int) Math.round(fraction * maxScroll)));
        if (grid) {
            scrollRow = row;
        } else {
            detailScroll = row;
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            dragScrollbar((int) event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    private void guideTo(QueryDto.ContainerHit hit) {
        ContainerHighlight.get().select(hit.pos(),
                minecraft.player.level().dimension().identifier().toString(),
                displayName(selectedItemId));
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int step = (int) Math.signum(deltaY);
        if (selectedItemId == null) {
            scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow - step));
        } else {
            // The list used to swallow the wheel silently, which read as a
            // frozen pane whenever an item was in more places than fit.
            detailScroll = Math.max(0, Math.min(maxDetailScroll(), detailScroll - step));
        }
        return true;
    }

    // Two signatures the Gfx facade cannot hide: 26.x renamed both the render
    // entry point and the background hook, and changed their parameter types as
    // part of the deferred-rendering rework. The window is drawn in the
    // background hook so the search field renders on top of it rather than under.
    //? if >=26.1 {
    /*@Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        drawWindow(new Gfx(graphics));
    }
    *///?} else {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        drawWindow(new Gfx(graphics));
    }
    //?}

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
