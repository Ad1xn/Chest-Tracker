package dev.adrian.chesttracker.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.index.IndexQuery;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.util.BlockKey;
import dev.adrian.chesttracker.net.ChestTrackerNetwork;
import dev.adrian.chesttracker.server.scan.LiveScanner;
import dev.adrian.chesttracker.server.scan.RegionScanner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

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
                .then(Commands.literal("scanworld")
                        .executes(ctx -> scanWorld(ctx.getSource()))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> cancelScan(ctx.getSource()))))
                .then(Commands.literal("access")
                        .executes(ctx -> showAccess(ctx.getSource()))
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (ChestTrackerConfig.Access tier : ChestTrackerConfig.Access.values()) {
                                        builder.suggest(tier.name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> setAccess(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "tier")))))
                .then(Commands.literal("stats")
                        .executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("find")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> find(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"))))));
    }

    private static int showAccess(CommandSourceStack source) {
        ChestTrackerConfig.Access tier = ChestTrackerConfig.get().permissionTier();
        source.sendSuccess(() -> Component.literal(
                "Players served: " + describe(tier) + " (" + tier.name() + ")"), false);
        return 1;
    }

    /**
     * Sets the tier live.
     *
     * <p>Exists because the alternative is stopping the server to edit a JSON
     * file - and because a tier that cannot be changed from in-game is one
     * nobody discovers is wrong until players complain.
     */
    private static int setAccess(CommandSourceStack source, String requested) {
        ChestTrackerConfig config = ChestTrackerConfig.get();
        ChestTrackerConfig.Access tier = ChestTrackerConfig.Access.parse(requested);

        // parse() falls back rather than failing, which is right for a config
        // file and wrong for a command: someone typing a tier deserves to know
        // it was not the one they named.
        if (!tier.name().equalsIgnoreCase(requested.trim())) {
            source.sendFailure(Component.literal(
                    "Unknown tier \"" + requested + "\". Use ALL, OWNED or OP."));
            return 0;
        }

        config.permissionTier = tier.name();
        config.save();

        // Anyone with the screen open is told at once, rather than finding out
        // the next time they happen to reopen it.
        ChestTrackerNetwork.announceAccess(source.getServer());

        source.sendSuccess(() -> Component.literal(
                "Players served: " + describe(tier) + " (" + tier.name() + ")"), true);
        return 1;
    }

    private static String describe(ChestTrackerConfig.Access tier) {
        return switch (tier) {
            case ALL -> "everyone";
            case OWNED -> "their own containers";
            case OP -> "operators only";
        };
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

    /**
     * Starts the background region scan: the whole world, including chunks that
     * are not loaded and never have been.
     */
    private static int scanWorld(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireTracker(source);
        RegionScanner scanner = Trackers.regionScanner();
        var server = Trackers.server();
        if (scanner == null || server == null) {
            source.sendFailure(Component.literal("ChestTracker is not active on this world."));
            return 0;
        }
        if (scanner.isRunning()) {
            source.sendFailure(Component.literal(
                    "A scan is already running. Use /chesttracker scanworld cancel to stop it."));
            return 0;
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        long tick = source.getLevel().getGameTime();
        if (!scanner.start(worldRoot, tick)) {
            source.sendFailure(Component.literal("Could not start the scan."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Scanning the world in the background. It reads region files directly, so "
                + "unloaded chunks are included. Check /chesttracker stats for progress."), false);
        return 1;
    }

    private static int cancelScan(CommandSourceStack source) {
        RegionScanner scanner = Trackers.regionScanner();
        if (scanner == null || !scanner.isRunning()) {
            source.sendFailure(Component.literal("No scan is running."));
            return 0;
        }
        scanner.cancel();
        source.sendSuccess(() -> Component.literal("Scan cancelled."), false);
        return 1;
    }

    private static int stats(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TrackerService tracker = requireTracker(source);

        source.sendSuccess(() -> Component.literal(
                "ChestTracker: " + tracker.totalContainers() + " containers across "
                        + tracker.dimensions().size() + " dimension(s)"), false);

        RegionScanner scanner = Trackers.regionScanner();
        if (scanner != null) {
            RegionScanner.Progress progress = scanner.progress();
            if (progress.running() || progress.regionsRead() > 0) {
                source.sendSuccess(() -> Component.literal(String.format(
                        "  %s region scan: %d/%d regions, %d chunks read, %d containers, "
                        + "%d skipped (loaded), %d unreadable, %d queued",
                        progress.running() ? "Running" : "Finished",
                        progress.regionsRead(), progress.regionsTotal(), progress.chunksRead(),
                        progress.containersFound(), progress.chunksSkippedLoaded(),
                        progress.chunksFailed(), scanner.queuedBatches())), false);
            }
        }

        for (String dimensionId : tracker.dimensions()) {
            WorldIndex.Stats stats = tracker.index(dimensionId).stats();
            source.sendSuccess(() -> Component.literal(String.format(
                    "  %s: %d containers, %d with known contents, %d unlooted, %d chunks, %d distinct items",
                    dimensionId, stats.containers(), stats.withKnownContents(),
                    stats.unlooted(), stats.chunks(), stats.distinctItems())), false);
            // Worth printing: it is the only way to check from in-game that
            // placement is being attributed at all, which the origin filter and
            // the OWNED tier both depend on.
            source.sendSuccess(() -> Component.literal(String.format(
                    "    origins: %d player-placed, %d natural, %d unknown",
                    stats.byOrigin().getOrDefault(dev.adrian.chesttracker.core.model.Origin.PLAYER_PLACED, 0),
                    stats.byOrigin().getOrDefault(dev.adrian.chesttracker.core.model.Origin.NATURAL, 0),
                    stats.byOrigin().getOrDefault(dev.adrian.chesttracker.core.model.Origin.UNKNOWN, 0))), false);
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
        IndexQuery query = IndexQuery.builder()
                .items(itemIds)
                .center(BlockKey.pack(origin.getX(), origin.getY(), origin.getZ()))
                .limit(MAX_RESULTS)
                .build();

        // Re-read any candidate whose chunk is loaded before showing it. Contents
        // in an unloaded chunk cannot have changed; contents in a loaded one can,
        // and a stale result is the failure that costs the mod its credibility.
        LiveScanner refresher = new LiveScanner(tracker);
        for (SearchResult candidate : tracker.search(dimensionId, query)) {
            refresher.refreshIfLoaded(player.level(), dimensionId, candidate.container().pos());
        }
        List<SearchResult> results = tracker.search(dimensionId, query);

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
