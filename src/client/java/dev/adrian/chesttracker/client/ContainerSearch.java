package dev.adrian.chesttracker.client;

import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.net.QueryDto;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Searching for an item without opening the search screen.
 *
 * <p>Hovering a stack and pressing the key is the shortest path there is from
 * "I want more of this" to being pointed at it: no window, no typing, no
 * reading a list. It answers straight into the guidance the screen already
 * hands off to, so the two routes end in the same place.
 */
public final class ContainerSearch {

    private ContainerSearch() {}

    /**
     * Containers asked for per search.
     *
     * <p>Matches the search screen's own cap. Guidance only ever points at one,
     * but the rest are what an in-world highlight will draw, so they are
     * fetched now rather than being a second query later.
     */
    private static final int MAX_TARGETS = 64;

    /**
     * Finds everywhere this item is and starts guiding to the nearest.
     *
     * <p>Filters come from the config rather than from the search screen's
     * toolbar: the screen's toggles belong to a window that is not open, and
     * inheriting whatever they were left on would make the key behave
     * differently depending on a screen the player cannot see.
     */
    public static void findAndGuide(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return;

        String label = stack.getHoverName().getString();

        if (!ClientTracker.isAvailable()) {
            say(unavailableMessage(), ChatFormatting.RED);
            return;
        }

        String dimensionId = player.level().dimension().identifier().toString();
        say("Looking for " + label + "...", ChatFormatting.GRAY);

        ClientTracker.containers(id.toString(), filters(), MAX_TARGETS).thenAccept(response ->
                client.execute(() -> {
                    List<QueryDto.ContainerHit> hits = response.hits();
                    if (hits.isEmpty()) {
                        say("Nothing indexed holds " + label, ChatFormatting.YELLOW);
                        return;
                    }
                    // The player may have walked through a portal while the
                    // server was answering; guiding them in the wrong world is
                    // worse than not answering.
                    LocalPlayer now = Minecraft.getInstance().player;
                    if (now == null
                            || !now.level().dimension().identifier().toString().equals(dimensionId)) {
                        return;
                    }
                    ContainerHighlight.get().select(
                            hits.stream().map(QueryDto.ContainerHit::pos).toList(),
                            dimensionId, label);
                }));
    }

    /**
     * The key was pressed with the cursor over nothing.
     *
     * <p>Worth a message. The alternative - staying silent - cannot be told
     * apart from the key being unbound or the mod being the wrong build, and a
     * player who cannot tell those apart has no way to work out which.
     */
    public static void sayNothingHovered() {
        say("Point at an item to search for it", ChatFormatting.GRAY);
    }

    private static QueryDto.Filters filters() {
        ChestTrackerConfig config = ChestTrackerConfig.get();
        return new QueryDto.Filters(config.includeNested, false, QueryDto.Filters.ORIGIN_ANY);
    }

    /**
     * Why nothing can be searched from here.
     *
     * <p>The same three cases the search screen distinguishes. One message for
     * all of them sends people looking in the wrong place.
     */
    private static String unavailableMessage() {
        return switch (ClientTracker.availability()) {
            case CONNECTING -> "Still asking the server...";
            case NOT_PERMITTED -> "This server does not allow searching.";
            default -> "No index here yet.";
        };
    }

    private static void say(String message, ChatFormatting colour) {
        ClientCompat.actionBar(Component.literal(message).withStyle(colour));
    }
}
