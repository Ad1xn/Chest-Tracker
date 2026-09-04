package dev.adrian.chesttracker.server;

import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.index.IndexQuery;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.net.QueryDto;
import dev.adrian.chesttracker.core.util.BlockKey;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Answers a {@link QueryDto} request against the index.
 *
 * <p>Both routes to the index end here: the client's own integrated server in
 * singleplayer, and the network handler on a dedicated server. That is the
 * point of the class. Two implementations of "what does this search return"
 * would drift, and the singleplayer one - which nobody exercises while working
 * on multiplayer - would be the one that rotted unnoticed.
 *
 * <p><b>Threading:</b> every method here touches the index, so every method
 * must be called on the server thread.
 */
public final class QueryService {

    /** Nothing the client asks for may exceed these, whatever it sends. */
    private static final int MAX_ITEMS = 1000;
    private static final int MAX_CONTAINERS = 128;

    /**
     * Block entity types whose contents churn constantly and are rarely
     * searched for. Filtering lives here rather than on the client because the
     * client may not have an index to filter, and because a limit applied
     * before filtering would spend result slots on rows nobody asked for.
     */
    private static final Set<String> MACHINES = Set.of(
            "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:brewing_stand", "minecraft:crafter", "minecraft:campfire",
            "minecraft:jukebox", "minecraft:lectern", "minecraft:decorated_pot",
            "minecraft:chiseled_bookshelf");

    private QueryService() {}

    // --- Permission ---------------------------------------------------------

    /**
     * Whether this player may query at all under {@code access}.
     *
     * <p>{@code OWNED} can always query - it simply sees less - so only
     * {@code OP} can refuse outright.
     */
    public static boolean mayQuery(ServerPlayer player, ChestTrackerConfig.Access access) {
        return access != ChestTrackerConfig.Access.OP || isOperator(player);
    }

    private static boolean isOperator(ServerPlayer player) {
        // The same bar the commands use: permission level 2, the usual "op".
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    /**
     * The owner a query must be restricted to, or null for no restriction.
     *
     * <p>An operator is not restricted even under {@code OWNED}: the tier is
     * about what ordinary players may see, and an op can read the same data out
     * of {@code /chesttracker find} anyway.
     */
    private static java.util.UUID ownerLimit(ServerPlayer player, ChestTrackerConfig.Access access) {
        if (access != ChestTrackerConfig.Access.OWNED) return null;
        return isOperator(player) ? null : player.getUUID();
    }

    // --- Queries ------------------------------------------------------------

    /** Totals every matching item, for the item-first grid. */
    public static QueryDto.SummaryResponse summarise(
            TrackerService tracker, ServerPlayer player,
            QueryDto.SummaryRequest request, ChestTrackerConfig.Access access) {

        if (!mayQuery(player, access)) return new QueryDto.SummaryResponse(request.requestId(), List.of());

        String dimensionId = Trackers.dimensionId(player.level());
        IndexQuery.Builder builder = IndexQuery.builder()
                .center(centreOf(player))
                .owner(ownerLimit(player, access));
        applyFilters(builder, request.filters(), tracker);

        String needle = normalise(request.text());
        if (!needle.isEmpty()) {
            Set<Integer> itemIds = matchingItemIds(tracker, needle);
            if (itemIds.isEmpty()) return new QueryDto.SummaryResponse(request.requestId(), List.of());
            builder.items(itemIds);
        }

        int limit = clamp(request.limit(), MAX_ITEMS);
        List<WorldIndex.ItemSummary> summaries = tracker.index(dimensionId).summarise(builder.build());

        List<QueryDto.ItemSummary> items = new java.util.ArrayList<>(Math.min(summaries.size(), limit));
        for (WorldIndex.ItemSummary summary : summaries) {
            if (items.size() >= limit) break;
            String itemId = tracker.palette().value(summary.itemId());
            // A palette id with no string behind it cannot be named on the wire,
            // and an unnamed row is no use to the client anyway.
            if (itemId == null) continue;
            items.add(new QueryDto.ItemSummary(itemId, summary.totalCount(),
                    summary.containerCount(), summary.nearestDistSq()));
        }
        return new QueryDto.SummaryResponse(request.requestId(), items);
    }

    /** The containers holding one item, nearest first. */
    public static QueryDto.ContainerResponse containers(
            TrackerService tracker, ServerPlayer player,
            QueryDto.ContainerRequest request, ChestTrackerConfig.Access access) {

        int id = request.requestId();
        if (!mayQuery(player, access)) return new QueryDto.ContainerResponse(id, List.of());

        int itemId = tracker.palette().lookup(request.itemId());
        // Never interned means nothing indexed has ever held it.
        if (itemId < 0) return new QueryDto.ContainerResponse(id, List.of());

        String dimensionId = Trackers.dimensionId(player.level());
        IndexQuery.Builder builder = IndexQuery.builder()
                .item(itemId)
                .center(centreOf(player))
                .owner(ownerLimit(player, access))
                .limit(clamp(request.limit(), MAX_CONTAINERS));
        applyFilters(builder, request.filters(), tracker);
        IndexQuery query = builder.build();

        List<SearchResult> results = tracker.search(dimensionId, query);
        // Only re-search when a refresh actually changed something. A refresh
        // can drop a container that is no longer there, so the first result
        // list cannot simply be reused - but usually nothing was stale and the
        // second search is skipped entirely.
        if (refreshStale(player.level(), dimensionId, results) > 0) {
            results = tracker.search(dimensionId, query);
        }
        List<QueryDto.ContainerHit> hits = new java.util.ArrayList<>(results.size());
        for (SearchResult result : results) {
            String typeId = tracker.palette().value(result.container().typeId());
            if (typeId == null) continue;
            hits.add(new QueryDto.ContainerHit(
                    typeId,
                    result.container().pos(),
                    result.matchedCount(),
                    result.distanceSq(),
                    hasNestedMatch(result),
                    result.container().origin() == Origin.NATURAL,
                    result.container().contentsKnown()));
        }
        return new QueryDto.ContainerResponse(id, hits);
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Brings any about-to-be-shown container up to date, if it is not already.
     *
     * <p>A stale result is the failure that costs the mod its credibility, but
     * that does not mean re-reading everything: live tracking already refreshes
     * changed containers every tick, so all but the ones still queued are
     * current. Only those are re-read here, and there are usually none.
     *
     * @return how many were actually re-read
     */
    private static int refreshStale(ServerLevel level, String dimensionId, List<SearchResult> results) {
        if (results.isEmpty()) return 0;
        List<Long> positions = new java.util.ArrayList<>(results.size());
        for (SearchResult result : results) positions.add(result.container().pos());
        return Trackers.refreshDirty(level, dimensionId, positions);
    }

    private static boolean hasNestedMatch(SearchResult result) {
        for (StackEntry entry : result.matches()) {
            if (entry.isNested()) return true;
        }
        return false;
    }

    private static long centreOf(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        return BlockKey.pack(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String normalise(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int requested, int ceiling) {
        return requested <= 0 ? ceiling : Math.min(requested, ceiling);
    }

    private static void applyFilters(IndexQuery.Builder builder, QueryDto.Filters filters,
                                     TrackerService tracker) {
        QueryDto.Filters effective = filters == null ? QueryDto.Filters.defaults() : filters;
        builder.includeNested(effective.includeNested());
        if (!effective.origins().isEmpty()) builder.origins(effective.origins());
        if (!effective.includeMachines()) builder.excludeTypes(machineTypeIds(tracker));
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

    private static Set<Integer> machineTypeIds(TrackerService tracker) {
        Set<Integer> ids = new HashSet<>();
        List<String> entries = tracker.palette().entries();
        for (int id = 0; id < entries.size(); id++) {
            if (MACHINES.contains(entries.get(id))) ids.add(id);
        }
        return ids;
    }
}
