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

    // --- Queries ------------------------------------------------------------

    /** Totals every indexed item, for the item-first grid. */
    public static CompletableFuture<QueryDto.SummaryResponse> summarise(
            String text, QueryDto.Filters filters, int limit) {

        int requestId = ServerLink.nextRequestId();
        QueryDto.SummaryRequest request = new QueryDto.SummaryRequest(requestId, text, filters, limit);

        if (!hasLocalIndex()) return ServerLink.summarise(request);

        return onServerThread(
                (tracker, player) -> QueryService.summarise(tracker, player, request, localAccess()),
                new QueryDto.SummaryResponse(requestId, List.of()));
    }

    /** The containers holding one item, nearest first. */
    public static CompletableFuture<QueryDto.ContainerResponse> containers(
            String itemId, QueryDto.Filters filters, int limit) {

        int requestId = ServerLink.nextRequestId();
        QueryDto.ContainerRequest request = new QueryDto.ContainerRequest(requestId, itemId, filters, limit);

        if (!hasLocalIndex()) return ServerLink.containers(request);

        return onServerThread(
                (tracker, player) -> QueryService.containers(tracker, player, request, localAccess()),
                new QueryDto.ContainerResponse(requestId, List.of()));
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
