package dev.adrian.chesttracker.client;

import dev.adrian.chesttracker.core.index.IndexQuery;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.util.BlockKey;
import dev.adrian.chesttracker.server.TrackerService;
import dev.adrian.chesttracker.server.Trackers;
import dev.adrian.chesttracker.server.scan.LiveScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The client's route to the index.
 *
 * <p>In singleplayer the client <em>is</em> the server, so this talks to the
 * integrated server's {@link TrackerService} directly - which is why
 * singleplayer gets full contents with no networking at all. Queries are
 * submitted to the server thread rather than run inline: the index is not
 * thread-safe, and the render thread must never touch it.
 *
 * <p>On a multiplayer server this returns nothing until the wire protocol
 * lands; that is the next phase, and the screen already treats results as
 * arriving asynchronously so it will not need reworking.
 */
public final class ClientTracker {

    private ClientTracker() {}

    /** True when there is an index we can query without networking. */
    public static boolean isAvailable() {
        Minecraft client = Minecraft.getInstance();
        return client.hasSingleplayerServer() && client.getSingleplayerServer() != null;
    }

    /**
     * Searches asynchronously.
     *
     * @param query free text matched against item ids; blank matches everything
     */
    public static CompletableFuture<List<SearchResult>> search(String query, int limit) {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        LocalPlayer player = client.player;
        if (server == null || player == null) return CompletableFuture.completedFuture(List.of());

        String dimensionId = player.level().dimension().identifier().toString();
        long centre = BlockKey.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        return server.submit(() -> {
            TrackerService tracker = Trackers.current();
            if (tracker == null) return List.<SearchResult>of();

            IndexQuery.Builder builder = IndexQuery.builder().center(centre).limit(limit);
            if (!needle.isEmpty()) {
                Set<Integer> itemIds = matchingItemIds(tracker, needle);
                if (itemIds.isEmpty()) return List.<SearchResult>of();
                builder.items(itemIds);
            }
            IndexQuery indexQuery = builder.build();

            // Re-read anything currently loaded before showing it, so results are
            // never stale for containers the game has in memory right now.
            ServerLevel level = Trackers.levelFor(dimensionId);
            if (level != null) {
                LiveScanner refresher = new LiveScanner(tracker);
                for (SearchResult candidate : tracker.search(dimensionId, indexQuery)) {
                    refresher.refreshIfLoaded(level, dimensionId, candidate.container().pos());
                }
            }
            return tracker.search(dimensionId, indexQuery);
        });
    }

    /** Substring match over interned item ids, so "diamond" finds every variant. */
    private static Set<Integer> matchingItemIds(TrackerService tracker, String needle) {
        Set<Integer> matches = new HashSet<>();
        List<String> entries = tracker.palette().entries();
        for (int id = 0; id < entries.size(); id++) {
            if (entries.get(id).toLowerCase(Locale.ROOT).contains(needle)) matches.add(id);
        }
        return matches;
    }

    /** Resolves a palette id back to a registry name, for display. */
    public static String nameOf(int paletteId) {
        TrackerService tracker = Trackers.current();
        if (tracker == null) return "?";
        String value = tracker.palette().value(paletteId);
        return value == null ? "?" : value;
    }
}
