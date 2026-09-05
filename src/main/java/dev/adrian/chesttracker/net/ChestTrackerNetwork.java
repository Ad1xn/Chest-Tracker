package dev.adrian.chesttracker.net;

import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.net.QueryDto;
import dev.adrian.chesttracker.platform.NetworkCompat;
import dev.adrian.chesttracker.server.QueryService;
import dev.adrian.chesttracker.server.TrackerService;
import dev.adrian.chesttracker.server.Trackers;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration and the server half of the protocol.
 *
 * <p>Payload <em>types</em> are registered from the common entrypoint on both
 * sides. A type registered on only one side makes that side unable to decode
 * what the other sends, which shows up as a disconnect rather than as a missing
 * feature, so the registration deliberately does not live next to the handlers.
 */
public final class ChestTrackerNetwork {

    /**
     * How often watching clients may be told the index moved.
     *
     * <p>A region scan applies thousands of records and an item sorter fires
     * many times a second, so the raw change rate is far higher than anything
     * worth redrawing. Twice a second reads as live and costs one query per
     * watcher at most.
     */
    private static final int SIGNAL_INTERVAL_TICKS = 10;

    /**
     * Players with the search screen open, and the index generation each was
     * last told about.
     *
     * <p>Keyed by uuid rather than holding the player: a {@code ServerPlayer}
     * is replaced on respawn and on a dimension change, so a held reference
     * would go stale and keep the old one alive.
     */
    private static final Map<UUID, Watcher> WATCHERS = new ConcurrentHashMap<>();

    private record Watcher(String dimensionId, long generation) {}

    private static int sinceLastSignal;

    private ChestTrackerNetwork() {}

    /** Runs on client and server alike, from the common entrypoint. */
    public static void registerTypes() {
        NetworkCompat.playC2S().register(
                ChestTrackerPayloads.SummaryRequestPayload.TYPE,
                ChestTrackerPayloads.SummaryRequestPayload.CODEC);
        NetworkCompat.playC2S().register(
                ChestTrackerPayloads.ContainerRequestPayload.TYPE,
                ChestTrackerPayloads.ContainerRequestPayload.CODEC);
        NetworkCompat.playC2S().register(
                ChestTrackerPayloads.SubscribePayload.TYPE,
                ChestTrackerPayloads.SubscribePayload.CODEC);
        NetworkCompat.playC2S().register(
                ChestTrackerPayloads.StatusRequestPayload.TYPE,
                ChestTrackerPayloads.StatusRequestPayload.CODEC);

        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.StatusResponsePayload.TYPE,
                ChestTrackerPayloads.StatusResponsePayload.CODEC);
        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.SummaryResponsePayload.TYPE,
                ChestTrackerPayloads.SummaryResponsePayload.CODEC);
        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.ContainerResponsePayload.TYPE,
                ChestTrackerPayloads.ContainerResponsePayload.CODEC);
        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.IndexChangedPayload.TYPE,
                ChestTrackerPayloads.IndexChangedPayload.CODEC);
        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.HelloPayload.TYPE,
                ChestTrackerPayloads.HelloPayload.CODEC);
    }

    /**
     * Installs the server handlers.
     *
     * <p>Fabric runs a play payload handler on the server thread, which is what
     * makes it safe to touch the index from here at all - {@code WorldIndex} is
     * not thread-safe and every other mutation is funnelled through that thread
     * too.
     */
    public static void registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.SummaryRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    TrackerService tracker = Trackers.current();
                    if (tracker == null) return;
                    QueryDto.SummaryResponse response =
                            QueryService.summarise(tracker, player, payload.request(), access());
                    send(player, new ChestTrackerPayloads.SummaryResponsePayload(response));
                });

        ServerPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.ContainerRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    TrackerService tracker = Trackers.current();
                    if (tracker == null) return;
                    QueryDto.ContainerResponse response =
                            QueryService.containers(tracker, player, payload.request(), access());
                    send(player, new ChestTrackerPayloads.ContainerResponsePayload(response));
                });

        ServerPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.StatusRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    TrackerService tracker = Trackers.current();
                    if (tracker == null) return;
                    QueryDto.StatusResponse response =
                            QueryService.status(tracker, player, payload.request(), access());
                    send(player, new ChestTrackerPayloads.StatusResponsePayload(response));
                });

        ServerPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.SubscribePayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    if (!payload.watching()) {
                        WATCHERS.remove(player.getUUID());
                        return;
                    }
                    // Seeded with the current generation: the client has just
                    // queried, so it is up to date by definition and does not
                    // need telling until something moves.
                    TrackerService tracker = Trackers.current();
                    if (tracker == null) return;
                    String dimensionId = Trackers.dimensionId(player.level());
                    WATCHERS.put(player.getUUID(),
                            new Watcher(dimensionId, tracker.generation(dimensionId)));
                });

        // Announce, rather than wait to be asked. A server without the mod
        // silently drops an unknown payload, so a client that asked first could
        // not tell "no mod here" from "still thinking" - it would have to guess
        // from a timeout either way, and would guess after every single query
        // instead of once per connection.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (Trackers.current() == null) return;
            // Guarded like any other send: at this point the client's channel
            // list may not have arrived yet, and announcing to a client that
            // cannot decode it would disconnect the very player we are greeting.
            // A missed greeting is not fatal - the client also treats any reply
            // to a real query as proof the server is here.
            send(player, new ChestTrackerPayloads.HelloPayload(new QueryDto.Hello(
                    QueryDto.Hello.PROTOCOL_VERSION,
                    QueryService.mayQuery(player, access()))));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player));
    }

    /**
     * Tells watching clients when their view has gone out of date.
     *
     * <p>Called every server tick, but acts on one tick in
     * {@link #SIGNAL_INTERVAL_TICKS}. A player is only signalled when their own
     * dimension's index has actually moved since they were last told, so a
     * scan running in the nether does not wake everyone in the overworld.
     */
    public static void flushChanges(MinecraftServer server) {
        if (WATCHERS.isEmpty()) return;
        if (++sinceLastSignal < SIGNAL_INTERVAL_TICKS) return;
        sinceLastSignal = 0;

        TrackerService tracker = Trackers.current();
        if (tracker == null) return;

        for (Map.Entry<UUID, Watcher> entry : WATCHERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                // Gone without closing the screen; nothing else cleans this up.
                WATCHERS.remove(entry.getKey());
                continue;
            }

            String dimensionId = Trackers.dimensionId(player.level());
            long generation = tracker.generation(dimensionId);
            Watcher watcher = entry.getValue();
            // A dimension change counts as "out of date" too: what they are
            // looking at describes a world they have left.
            if (watcher.generation() == generation && watcher.dimensionId().equals(dimensionId)) continue;

            WATCHERS.put(entry.getKey(), new Watcher(dimensionId, generation));
            send(player, ChestTrackerPayloads.IndexChangedPayload.INSTANCE);
        }
    }

    /** Forgets a disconnecting player, so a closed connection cannot be signalled. */
    public static void forget(ServerPlayer player) {
        WATCHERS.remove(player.getUUID());
    }

    /**
     * Re-greets everyone after the tier changes.
     *
     * <p>A greeting is otherwise only sent at join, so without this a player
     * already connected keeps the permission they were told about then. Their
     * next query would correct it, but a screen sitting open makes no queries.
     */
    public static void announceAccess(MinecraftServer server) {
        if (server == null || Trackers.current() == null) return;
        ChestTrackerConfig.Access tier = access();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, new ChestTrackerPayloads.HelloPayload(new QueryDto.Hello(
                    QueryDto.Hello.PROTOCOL_VERSION, QueryService.mayQuery(player, tier))));
        }
    }

    /**
     * Forgets everyone, on shutdown.
     *
     * <p>A client leaving a singleplayer world and opening another keeps the
     * same process, so without this the next world starts with the last one's
     * watchers still listed.
     */
    public static void forgetAll() {
        WATCHERS.clear();
        sinceLastSignal = 0;
    }

    /**
     * The tier this server applies.
     *
     * <p>Read per request rather than captured at startup, so a change made
     * through the settings screen applies to the next query. Editing the JSON
     * by hand still needs a restart - {@link ChestTrackerConfig#get()} holds
     * one instance for the process and never re-reads the file.
     */
    private static ChestTrackerConfig.Access access() {
        return ChestTrackerConfig.get().permissionTier();
    }

    /**
     * Sends a payload, skipping a client that cannot decode it.
     *
     * <p>A vanilla client is disconnected by the server sending it a payload it
     * never registered, so this must be checked and not assumed - our own reply
     * would be the thing that kicked them.
     */
    private static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (!ServerPlayNetworking.canSend(player, payload.type())) {
            ChestTracker.LOG.debug("{} cannot receive {}", player.getName().getString(), payload.type().id());
            return;
        }
        ServerPlayNetworking.send(player, payload);
    }
}
