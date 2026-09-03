package dev.adrian.chesttracker.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.adrian.chesttracker.core.index.IndexQuery;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.util.BlockKey;
import dev.adrian.chesttracker.server.scan.LiveScanner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Commands usable from a vanilla client, so the server half is worth running on
 * its own rather than only as a backend for the client mod.
 */
public final class ChestTrackerCommands {

    private static final int DEFAULT_RADIUS_CHUNKS = 8;
    private static final int MAX_RESULTS = 20;

    private ChestTrackerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chesttracker")
                // LEVEL_GAMEMASTERS is permission level 2, the usual "op" bar. A full
                // world index is effectively loot x-ray, so it stays op-gated.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("scan")
                        .executes(ctx -> scan(ctx.getSource(), DEFAULT_RADIUS_CHUNKS))
                        .then(Commands.argument("chunkRadius", IntegerArgumentType.integer(0, 64))
                                .executes(ctx -> scan(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "chunkRadius")))))
                .then(Commands.literal("stats")
                        .executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("find")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> find(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"))))));
    }

    private static int scan(CommandSourceStack source, int chunkRadius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TrackerService tracker = requireTracker(source);
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();

        LiveScanner.ScanResult result =
                new LiveScanner(tracker).scanAround(level, player.blockPosition(), chunkRadius);

        source.sendSuccess(() -> Component.literal(String.format(
                "Scanned %d chunks (%d not loaded): %d containers indexed, %d stale entries removed.",
                result.chunksScanned(), result.chunksSkipped(),
                result.containersFound(), result.staleRemoved())), false);

        if (result.chunksSkipped() > 0) {
            source.sendSuccess(() -> Component.literal(
                    "Unloaded chunks are skipped here; the offline region scanner covers those."), false);
        }
        return result.containersFound();
    }

    private static int stats(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TrackerService tracker = requireTracker(source);

        source.sendSuccess(() -> Component.literal(
                "ChestTracker: " + tracker.totalContainers() + " containers across "
                        + tracker.dimensions().size() + " dimension(s)"), false);

        for (String dimensionId : tracker.dimensions()) {
            WorldIndex.Stats stats = tracker.index(dimensionId).stats();
            source.sendSuccess(() -> Component.literal(String.format(
                    "  %s: %d containers, %d with known contents, %d unlooted, %d chunks, %d distinct items",
                    dimensionId, stats.containers(), stats.withKnownContents(),
                    stats.unlooted(), stats.chunks(), stats.distinctItems())), false);
        }
        return tracker.totalContainers();
    }

    private static int find(CommandSourceStack source, String itemQuery) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TrackerService tracker = requireTracker(source);
        ServerPlayer player = source.getPlayerOrException();
        String dimensionId = Trackers.dimensionId(player.level());

        Set<Integer> itemIds = matchingItemIds(tracker, itemQuery);
        if (itemIds.isEmpty()) {
            source.sendFailure(Component.literal("Nothing indexed matches \"" + itemQuery + "\"."));
            return 0;
        }

        var origin = player.blockPosition();
        List<SearchResult> results = tracker.search(dimensionId, IndexQuery.builder()
                .items(itemIds)
                .center(BlockKey.pack(origin.getX(), origin.getY(), origin.getZ()))
                .limit(MAX_RESULTS)
                .build());

        if (results.isEmpty()) {
            source.sendFailure(Component.literal("No indexed container holds that."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Found " + results.size() + " container(s):"), false);
        for (SearchResult result : results) {
            long pos = result.container().pos();
            String line = String.format("  %d x %s at %s (%.0fm)",
                    result.matchedCount(),
                    tracker.palette().value(result.matches().get(0).itemId()),
                    BlockKey.toString(pos),
                    result.distance());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return results.size();
    }

    /** Substring match over interned item ids, so "diamond" finds every variant. */
    private static Set<Integer> matchingItemIds(TrackerService tracker, String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT).trim();
        Set<Integer> matches = new HashSet<>();
        List<String> entries = tracker.palette().entries();
        for (int id = 0; id < entries.size(); id++) {
            if (entries.get(id).toLowerCase(java.util.Locale.ROOT).contains(needle)) matches.add(id);
        }
        return matches;
    }

    private static TrackerService requireTracker(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TrackerService tracker = Trackers.current();
        if (tracker == null) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("ChestTracker is not active on this world.")).create();
        }
        return tracker;
    }
}
