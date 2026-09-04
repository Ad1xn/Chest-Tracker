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
import net.minecraft.server.level.ServerPlayer;

/**
 * Registration and the server half of the protocol.
 *
 * <p>Payload <em>types</em> are registered from the common entrypoint on both
 * sides. A type registered on only one side makes that side unable to decode
 * what the other sends, which shows up as a disconnect rather than as a missing
 * feature, so the registration deliberately does not live next to the handlers.
 */
public final class ChestTrackerNetwork {

    private ChestTrackerNetwork() {}

    /** Runs on client and server alike, from the common entrypoint. */
    public static void registerTypes() {
        NetworkCompat.playC2S().register(
                ChestTrackerPayloads.SummaryRequestPayload.TYPE,
                ChestTrackerPayloads.SummaryRequestPayload.CODEC);
        NetworkCompat.playC2S().register(
                ChestTrackerPayloads.ContainerRequestPayload.TYPE,
                ChestTrackerPayloads.ContainerRequestPayload.CODEC);

        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.SummaryResponsePayload.TYPE,
                ChestTrackerPayloads.SummaryResponsePayload.CODEC);
        NetworkCompat.playS2C().register(
                ChestTrackerPayloads.ContainerResponsePayload.TYPE,
                ChestTrackerPayloads.ContainerResponsePayload.CODEC);
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
