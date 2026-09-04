package dev.adrian.chesttracker.client.net;

import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.net.QueryDto;
import dev.adrian.chesttracker.net.ChestTrackerPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client's side of the wire: what the server is, and what we asked it.
 *
 * <p>Whether a server has the mod cannot be discovered by asking. An unknown
 * custom payload is dropped without a reply, so "no mod" and "not answered yet"
 * look identical - which is why the server announces itself on join and this
 * waits a grace period before concluding there is nothing there.
 *
 * <p>Two things settle it, not one. The announcement is the normal route, but
 * the channel list a server uses to decide whether it may send us anything can
 * still be in flight when we join, so <em>any</em> reply to a real query is
 * also taken as proof. Only silence for the whole grace period means absent.
 *
 * <p>Replies are matched to requests by id rather than assumed to arrive in
 * order. Typing in the search box starts a query per keystroke, and on a
 * connection with any jitter an early reply can land after a later one.
 */
public final class ServerLink {

    /** What we know about the far end. */
    public enum State {
        /** Joined, nothing heard yet, still inside the grace period. */
        WAITING,
        /** The server has the mod. */
        PRESENT,
        /** Grace period elapsed in silence. */
        ABSENT
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    /** Outstanding requests, by correlation id. */
    private static final Map<Integer, Pending<?>> PENDING = new ConcurrentHashMap<>();

    private static volatile State state = State.ABSENT;
    private static volatile boolean canQuery;
    private static volatile long joinedAt;

    /**
     * One unanswered request.
     *
     * <p>{@code type} is carried so a reply can be checked against what was
     * actually asked for. Request ids come off the wire, and a reply quoting an
     * id that belongs to the other kind of query would otherwise complete a
     * future with the wrong type - erasure lets that through here and fails far
     * away, in whatever consumed the result.
     */
    private record Pending<T>(Class<T> type, CompletableFuture<T> future, T empty, long deadline) {}

    private ServerLink() {}

    // --- Setup --------------------------------------------------------------

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.HelloPayload.TYPE,
                (payload, context) -> {
                    // A future protocol we cannot speak is no better than none.
                    if (payload.hello().protocolVersion() != QueryDto.Hello.PROTOCOL_VERSION) {
                        state = State.ABSENT;
                        return;
                    }
                    state = State.PRESENT;
                    canQuery = payload.hello().canQuery();
                });

        ClientPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.SummaryResponsePayload.TYPE,
                (payload, context) -> deliver(payload.response().requestId(), payload.response()));

        ClientPlayNetworking.registerGlobalReceiver(
                ChestTrackerPayloads.ContainerResponsePayload.TYPE,
                (payload, context) -> deliver(payload.response().requestId(), payload.response()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Every connection starts over: the last server's answer says
            // nothing about this one.
            failAllPending();
            state = State.WAITING;
            canQuery = false;
            joinedAt = System.currentTimeMillis();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            failAllPending();
            state = State.ABSENT;
            canQuery = false;
        });
    }

    // --- State --------------------------------------------------------------

    public static State state() {
        return state;
    }

    /** True once we know the server is there and this player is allowed to ask. */
    public static boolean canQuery() {
        return state == State.PRESENT && canQuery;
    }

    /**
     * Expires the grace period and any overdue request.
     *
     * <p>Driven from the client tick rather than a timer thread: everything it
     * completes is consumed on the client thread, and a request whose reply
     * never comes must still complete or the screen waits forever.
     */
    public static void tick() {
        long now = System.currentTimeMillis();

        if (state == State.WAITING && now - joinedAt > graceMs()) {
            state = State.ABSENT;
        }

        if (PENDING.isEmpty()) return;
        for (Iterator<Map.Entry<Integer, Pending<?>>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Pending<?>> entry = it.next();
            if (now < entry.getValue().deadline()) continue;
            it.remove();
            completeEmpty(entry.getValue());
        }
    }

    private static long graceMs() {
        return Math.max(250, ChestTrackerConfig.get().serverHelloTimeoutMs);
    }

    // --- Requests -----------------------------------------------------------

    public static int nextRequestId() {
        return NEXT_ID.getAndIncrement();
    }

    public static CompletableFuture<QueryDto.SummaryResponse> summarise(QueryDto.SummaryRequest request) {
        return send(request.requestId(), QueryDto.SummaryResponse.class,
                new ChestTrackerPayloads.SummaryRequestPayload(request),
                new QueryDto.SummaryResponse(request.requestId(), List.of()));
    }

    public static CompletableFuture<QueryDto.ContainerResponse> containers(QueryDto.ContainerRequest request) {
        return send(request.requestId(), QueryDto.ContainerResponse.class,
                new ChestTrackerPayloads.ContainerRequestPayload(request),
                new QueryDto.ContainerResponse(request.requestId(), List.of()));
    }

    /**
     * Sends a request and registers what to answer with if nothing comes back.
     *
     * <p>The empty response is not an error case the caller has to handle: an
     * unreachable index and an index with no matches look the same to the
     * screen, which is exactly right - both mean "nothing to show".
     */
    private static <T> CompletableFuture<T> send(int requestId, Class<T> type,
                                                 CustomPacketPayload payload, T empty) {
        // Sending a payload the server never registered is what gets a client
        // disconnected, so this is a guard rather than an optimisation.
        if (!ClientPlayNetworking.canSend(payload.type())) {
            return CompletableFuture.completedFuture(empty);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        PENDING.put(requestId, new Pending<>(type, future, empty, System.currentTimeMillis() + graceMs()));
        ClientPlayNetworking.send(payload);
        return future;
    }

    private static void deliver(int requestId, Object response) {
        // A reply of any kind proves the server has the mod, even if the
        // announcement was missed because our channel list arrived late.
        if (state == State.WAITING) {
            state = State.PRESENT;
            canQuery = true;
        }

        Pending<?> pending = PENDING.get(requestId);
        // No entry means it already timed out, or the reply is for a request
        // from a previous connection. Either way the screen has moved on.
        if (pending == null) return;

        // A reply quoting the id of a different kind of query is left pending
        // to time out, rather than completed with a value its caller cannot
        // hold - erasure would let that through here and fail somewhere else.
        if (!pending.type().isInstance(response)) return;

        PENDING.remove(requestId);
        complete(pending, response);
    }

    private static <T> void complete(Pending<T> pending, Object response) {
        pending.future().complete(pending.type().cast(response));
    }

    private static <T> void completeEmpty(Pending<T> pending) {
        pending.future().complete(pending.empty());
    }

    private static void failAllPending() {
        for (Iterator<Map.Entry<Integer, Pending<?>>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Pending<?> pending = it.next().getValue();
            it.remove();
            completeEmpty(pending);
        }
    }
}
