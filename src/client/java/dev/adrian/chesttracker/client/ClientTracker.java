package dev.adrian.chesttracker.client;

import dev.adrian.chesttracker.client.net.ServerLink;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.net.QueryDto;
import dev.adrian.chesttracker.server.QueryService;
import dev.adrian.chesttracker.server.TrackerService;
import dev.adrian.chesttracker.server.Trackers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The client's route to the index, whichever end it lives on.
 *
 * <p>Both routes produce the same {@link QueryDto} shapes, and both run the
 * same {@link QueryService}. In singleplayer that service is called directly on
 * the integrated server's thread; on a remote server the request goes over the
 * wire and the far end calls it. The screen cannot tell the difference, which
 * is the point: a singleplayer-only shortcut would be the code path nobody
 * exercises while working on multiplayer, and the one that quietly breaks.
 *
 * <p>Queries are never run inline. The index is not thread-safe and the render
 * thread must never touch it, so even the local path is submitted to the server
 * thread and answered asynchronously.
 */
public final class ClientTracker {

    private ClientTracker() {}

    /** Why the screen can or cannot show anything. */
    public enum Availability {
        /** Our own world; full access, no networking. */
        LOCAL,
        /** A server with the mod, and permission to ask it. */
        SERVER,
        /** A server with the mod that will not answer this player. */
        NOT_PERMITTED,
        /** Still deciding - the server has not announced itself yet. */
        CONNECTING,
        /** No index reachable from here. */
        NONE
    }

    public static Availability availability() {
        if (hasLocalIndex()) return Availability.LOCAL;
        return switch (ServerLink.state()) {
            case WAITING -> Availability.CONNECTING;
            case PRESENT -> ServerLink.canQuery() ? Availability.SERVER : Availability.NOT_PERMITTED;
            case ABSENT -> Availability.NONE;
        };
    }

    /** True when there is an index we can query without networking. */
    public static boolean isAvailable() {
        return availability() == Availability.LOCAL || availability() == Availability.SERVER;
    }

    private static boolean hasLocalIndex() {
        Minecraft client = Minecraft.getInstance();
        return client.hasSingleplayerServer() && client.getSingleplayerServer() != null;
    }

    // --- Live updates -------------------------------------------------------

    /**
     * A value that changes whenever what the screen is showing may be out of
     * date.
     *
     * <p>One token for both routes, so the screen has no idea whether a network
     * was involved. On a server it counts pushes; in our own world it reads the
     * index's own change counter directly, which needs no networking and no
     * subscription at all.
     */
    public static long changeToken() {
        if (!hasLocalIndex()) return ServerLink.changeToken();

        TrackerService tracker = Trackers.current();
        LocalPlayer player = Minecraft.getInstance().player;
        if (tracker == null || player == null) return 0L;
        // A plain volatile read; safe from the render thread, unlike the index.
        return tracker.generation(player.level().dimension().identifier().toString());
    }

    /**
     * Says whether the screen is open, so a server only pushes to watchers.
     *
     * <p>Does nothing in our own world - there is nothing to subscribe to when
     * the index is right here.
     */
    public static void setWatching(boolean watching) {
        if (hasLocalIndex()) return;
        ServerLink.setWatching(watching);
    }

    // --- Queries ------------------------------------------------------------

    /** Totals every indexed item, for the item-first grid. */
    public static CompletableFuture<QueryDto.SummaryResponse> summarise(
            String text, QueryDto.Filters filters, int limit) {
        return summarise(text, filters, limit, "");
    }

    /** As above, but for a named dimension; blank means where the player is. */
    public static CompletableFuture<QueryDto.SummaryResponse> summarise(
            String text, QueryDto.Filters filters, int limit, String dimensionId) {

        int requestId = ServerLink.nextRequestId();
        QueryDto.SummaryRequest request =
                new QueryDto.SummaryRequest(requestId, text, filters, limit, dimensionId);

        if (!hasLocalIndex()) return ServerLink.summarise(request);

        return onServerThread(
                (tracker, player) -> QueryService.summarise(tracker, player, request, localAccess()),
                QueryDto.SummaryResponse.of(requestId, List.of()));
    }

    /** The containers holding one item, nearest first. */
    public static CompletableFuture<QueryDto.ContainerResponse> containers(
            String itemId, QueryDto.Filters filters, int limit) {
        return containers(itemId, filters, limit, "");
    }

    /** As above, but for a named dimension; blank means where the player is. */
    public static CompletableFuture<QueryDto.ContainerResponse> containers(
            String itemId, QueryDto.Filters filters, int limit, String dimensionId) {

        int requestId = ServerLink.nextRequestId();
        QueryDto.ContainerRequest request =
                new QueryDto.ContainerRequest(requestId, itemId, filters, limit, dimensionId);

        if (!hasLocalIndex()) return ServerLink.containers(request);

        return onServerThread(
                (tracker, player) -> QueryService.containers(tracker, player, request, localAccess()),
                QueryDto.ContainerResponse.of(requestId, List.of()));
    }

    /**
     * What the index holds, and whether it is still filling.
     *
     * <p>Same two routes as every other query, so the screen cannot tell
     * whether a network was involved.
     */
    public static CompletableFuture<QueryDto.StatusResponse> status() {
        int requestId = ServerLink.nextRequestId();
        QueryDto.StatusRequest request = new QueryDto.StatusRequest(requestId);

        if (!hasLocalIndex()) return ServerLink.status(request);

        return onServerThread(
                (tracker, player) -> QueryService.status(tracker, player, request, localAccess()),
                QueryDto.StatusResponse.empty(requestId));
    }

    /**
     * Our own world is never gated.
     *
     * <p>The configured tier governs players arriving over the network. This
     * path is only ever the host querying the world they are playing, and
     * making them op themselves to search their own chests would be absurd.
     */
    private static ChestTrackerConfig.Access localAccess() {
        return ChestTrackerConfig.Access.ALL;
    }

    private interface LocalQuery<T> {
        T run(TrackerService tracker, ServerPlayer player);
    }

    /**
     * Runs a query on the integrated server's thread.
     *
     * <p>It resolves the <em>server's</em> player rather than using the local
     * one, so the query centre and dimension come from the same authoritative
     * place they would on a real server.
     */
    private static <T> CompletableFuture<T> onServerThread(LocalQuery<T> query, T empty) {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        LocalPlayer local = client.player;
        if (server == null || local == null) return CompletableFuture.completedFuture(empty);

        java.util.UUID uuid = local.getUUID();
        return server.submit(() -> {
            TrackerService tracker = Trackers.current();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (tracker == null || player == null) return empty;
            return query.run(tracker, player);
        });
    }
}
