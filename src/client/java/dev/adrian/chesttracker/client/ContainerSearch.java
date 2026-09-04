package dev.adrian.chesttracker.client;

import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.net.QueryDto;
import dev.adrian.chesttracker.core.util.BlockKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
     *
     * @return whether a search actually went out, so the caller knows whether
     *         to close the container the player was looking in
     */
    public static boolean findAndGuide(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return false;

        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;

        String label = stack.getHoverName().getString();

        if (!ClientTracker.isAvailable()) {
            // Not a hit, so the caller leaves the container open - the player is
            // still standing at it and has been told why nothing happened.
            say(unavailableMessage(), ChatFormatting.RED);
            return false;
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
                    // The highlight goes up either way. Opening one container
                    // does not answer where the other nine are, and the boxes
                    // are still what says so.
                    ContainerHighlight.get().select(
                            hits.stream().map(QueryDto.ContainerHit::pos).toList(),
                            dimensionId, label);
                    openIfInReach(hits, now);
                }));
        return true;
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

    /**
     * The search screen's own filters, as the player last left them.
     *
     * <p>Read from the config rather than invented here: those three values are
     * what the menu writes when it closes, so the key searches what the menu
     * says it will. Hardcoding them meant the key quietly ignored every filter
     * the player had set - it always hid machines and always counted every
     * origin, whatever the menu was showing.
     */
    private static QueryDto.Filters filters() {
        ChestTrackerConfig config = ChestTrackerConfig.get();
        return new QueryDto.Filters(config.includeNested, config.showMachines, config.originFilter);
    }

    /**
     * Opens the richest match already within arm's reach, if there is one.
     *
     * <p>Standing at the chest and being told to walk to it is the one case
     * where guidance is worse than useless, so the mod just opens it. "Richest"
     * rather than "nearest" because two chests at the same counter are the same
     * distance and the question was where the item is, not where a chest is.
     *
     * <p>Falls through silently when nothing is in reach, leaving the boxes to
     * do their job - and refuses to act on a position the world no longer
     * agrees is a container, because the index can be a few ticks stale and
     * right-clicking thin air with a block in hand places it.
     *
     * @return true if a container was opened
     */
    private static boolean openIfInReach(List<QueryDto.ContainerHit> hits, LocalPlayer player) {
        if (!ChestTrackerConfig.get().openInReach) return false;
        Minecraft client = Minecraft.getInstance();
        // Never while something is already on screen: the search that started
        // this may have been run from a container that has not closed yet.
        if (client.level == null || ClientCompat.currentScreen() != null) return false;

        double reach = player.blockInteractionRange();
        QueryDto.ContainerHit best = null;
        BlockPos bestPos = null;

        for (QueryDto.ContainerHit hit : hits) {
            BlockPos pos = new BlockPos(BlockKey.x(hit.pos()), BlockKey.y(hit.pos()), BlockKey.z(hit.pos()));
            if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach) continue;
            // The index says there is a container here; the world has the vote.
            if (!(client.level.getBlockEntity(pos) instanceof Container)) continue;
            if (best == null || hit.matchedCount() > best.matchedCount()) {
                best = hit;
                bestPos = pos;
            }
        }

        if (best == null) return false;

        BlockHitResult where = new BlockHitResult(Vec3.atCenterOf(bestPos), Direction.UP, bestPos, false);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, where);
        return true;
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
