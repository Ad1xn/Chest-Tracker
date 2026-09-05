package dev.adrian.chesttracker.net;

import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.core.net.QueryDto;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire types, one per {@link QueryDto} shape.
 *
 * <p>The codecs are written out by hand against {@link FriendlyByteBuf} rather
 * than composed from registry codecs, because every field is a primitive or a
 * registry <em>string</em>. That is the whole point of the DTOs: an item is
 * named {@code minecraft:redstone} on the wire, never by a palette id, because
 * the two sides' palettes are built independently and the same int means a
 * different item on each.
 *
 * <p>Typed against {@code FriendlyByteBuf} rather than {@code
 * RegistryFriendlyByteBuf} so one codec serves both directions - the play
 * registries hand out the registry-aware subtype, and nothing here needs it.
 *
 * <p>Strings are length-capped on read. A payload is attacker-controlled input
 * in both directions, and an uncapped {@code readUtf} lets one packet ask the
 * other side to allocate far more than the packet's own size.
 */
public final class ChestTrackerPayloads {

    /** Item and container ids; comfortably above any real registry name. */
    private static final int MAX_ID = 256;
    /** Search text. The client's own box caps at 64. */
    private static final int MAX_TEXT = 128;
    /** Enough for the largest list either side will ever be asked to render. */
    private static final int MAX_ENTRIES = 2048;

    private ChestTrackerPayloads() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ChestTracker.MOD_ID, path);
    }

    // --- Filters ------------------------------------------------------------

    private static void writeFilters(FriendlyByteBuf buf, QueryDto.Filters filters) {
        QueryDto.Filters effective = filters == null ? QueryDto.Filters.defaults() : filters;
        buf.writeBoolean(effective.includeNested());
        buf.writeBoolean(effective.includeMachines());
        buf.writeVarInt(effective.originFilter());
    }

    private static QueryDto.Filters readFilters(FriendlyByteBuf buf) {
        // The record's own constructor rejects an out-of-range origin.
        return new QueryDto.Filters(buf.readBoolean(), buf.readBoolean(), buf.readVarInt());
    }

    /**
     * Reads a length-prefixed list, refusing an implausible count before
     * allocating for it.
     */
    private static <T> List<T> readList(FriendlyByteBuf buf, java.util.function.Function<FriendlyByteBuf, T> reader) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("ChestTracker payload declared " + count + " entries");
        }
        List<T> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(reader.apply(buf));
        return values;
    }

    // --- Summary ------------------------------------------------------------

    /** Client asks for item totals. */
    public record SummaryRequestPayload(QueryDto.SummaryRequest request) implements CustomPacketPayload {

        public static final Type<SummaryRequestPayload> TYPE = new Type<>(id("summary_request"));

        public static final StreamCodec<FriendlyByteBuf, SummaryRequestPayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            QueryDto.SummaryRequest request = payload.request();
                            buf.writeVarInt(request.requestId());
                            buf.writeUtf(request.text() == null ? "" : request.text(), MAX_TEXT);
                            writeFilters(buf, request.filters());
                            buf.writeVarInt(request.limit());
                            buf.writeUtf(request.dimensionId(), MAX_ID);
                        },
                        buf -> new SummaryRequestPayload(new QueryDto.SummaryRequest(
                                buf.readVarInt(), buf.readUtf(MAX_TEXT), readFilters(buf),
                                buf.readVarInt(), buf.readUtf(MAX_ID))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server replies with item totals. */
    public record SummaryResponsePayload(QueryDto.SummaryResponse response) implements CustomPacketPayload {

        public static final Type<SummaryResponsePayload> TYPE = new Type<>(id("summary_response"));

        public static final StreamCodec<FriendlyByteBuf, SummaryResponsePayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            QueryDto.SummaryResponse response = payload.response();
                            buf.writeVarInt(response.requestId());
                            buf.writeBoolean(response.permitted());
                            buf.writeVarInt(response.items().size());
                            for (QueryDto.ItemSummary item : response.items()) {
                                buf.writeUtf(item.itemId(), MAX_ID);
                                buf.writeVarInt(item.totalCount());
                                buf.writeVarInt(item.containerCount());
                                buf.writeVarInt(item.nestedCount());
                                buf.writeDouble(item.nearestDistSq());
                            }
                        },
                        buf -> {
                            int requestId = buf.readVarInt();
                            boolean permitted = buf.readBoolean();
                            return new SummaryResponsePayload(new QueryDto.SummaryResponse(requestId, permitted,
                                    readList(buf, b -> new QueryDto.ItemSummary(
                                            b.readUtf(MAX_ID), b.readVarInt(), b.readVarInt(),
                                            b.readVarInt(), b.readDouble()))));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // --- Status -------------------------------------------------------------

    public record StatusRequestPayload(QueryDto.StatusRequest request) implements CustomPacketPayload {

        public static final Type<StatusRequestPayload> TYPE = new Type<>(id("status_request"));

        public static final StreamCodec<FriendlyByteBuf, StatusRequestPayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> buf.writeVarInt(payload.request().requestId()),
                        buf -> new StatusRequestPayload(new QueryDto.StatusRequest(buf.readVarInt())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StatusResponsePayload(QueryDto.StatusResponse response) implements CustomPacketPayload {

        public static final Type<StatusResponsePayload> TYPE = new Type<>(id("status_response"));

        public static final StreamCodec<FriendlyByteBuf, StatusResponsePayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            QueryDto.StatusResponse response = payload.response();
                            buf.writeVarInt(response.requestId());
                            buf.writeBoolean(response.scanning());
                            buf.writeVarInt(response.regionsRead());
                            buf.writeVarInt(response.regionsTotal());
                            buf.writeVarInt(response.chunksRead());
                            buf.writeVarInt(response.dimensions().size());
                            for (QueryDto.DimensionSummary dimension : response.dimensions()) {
                                buf.writeUtf(dimension.dimensionId(), MAX_ID);
                                buf.writeVarInt(dimension.containers());
                            }
                        },
                        buf -> new StatusResponsePayload(new QueryDto.StatusResponse(
                                buf.readVarInt(), buf.readBoolean(),
                                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                                readList(buf, b -> new QueryDto.DimensionSummary(
                                        b.readUtf(MAX_ID), b.readVarInt())))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // --- Containers ---------------------------------------------------------

    /** Client asks where one item is. */
    public record ContainerRequestPayload(QueryDto.ContainerRequest request) implements CustomPacketPayload {

        public static final Type<ContainerRequestPayload> TYPE = new Type<>(id("container_request"));

        public static final StreamCodec<FriendlyByteBuf, ContainerRequestPayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            QueryDto.ContainerRequest request = payload.request();
                            buf.writeVarInt(request.requestId());
                            buf.writeUtf(request.itemId() == null ? "" : request.itemId(), MAX_ID);
                            writeFilters(buf, request.filters());
                            buf.writeVarInt(request.limit());
                            buf.writeUtf(request.dimensionId(), MAX_ID);
                        },
                        buf -> new ContainerRequestPayload(new QueryDto.ContainerRequest(
                                buf.readVarInt(), buf.readUtf(MAX_ID), readFilters(buf),
                                buf.readVarInt(), buf.readUtf(MAX_ID))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server replies with the containers holding it. */
    public record ContainerResponsePayload(QueryDto.ContainerResponse response) implements CustomPacketPayload {

        public static final Type<ContainerResponsePayload> TYPE = new Type<>(id("container_response"));

        public static final StreamCodec<FriendlyByteBuf, ContainerResponsePayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            QueryDto.ContainerResponse response = payload.response();
                            buf.writeVarInt(response.requestId());
                            buf.writeBoolean(response.permitted());
                            buf.writeVarInt(response.hits().size());
                            for (QueryDto.ContainerHit hit : response.hits()) {
                                buf.writeUtf(hit.typeId(), MAX_ID);
                                buf.writeLong(hit.pos());
                                buf.writeVarInt(hit.matchedCount());
                                buf.writeDouble(hit.distanceSq());
                                buf.writeBoolean(hit.nested());
                                buf.writeBoolean(hit.natural());
                                buf.writeBoolean(hit.contentsKnown());
                            }
                        },
                        buf -> {
                            int requestId = buf.readVarInt();
                            boolean permitted = buf.readBoolean();
                            return new ContainerResponsePayload(new QueryDto.ContainerResponse(requestId, permitted,
                                    readList(buf, b -> new QueryDto.ContainerHit(
                                            b.readUtf(MAX_ID), b.readLong(), b.readVarInt(), b.readDouble(),
                                            b.readBoolean(), b.readBoolean(), b.readBoolean()))));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // --- Live updates -------------------------------------------------------

    /**
     * Client tells the server whether it is currently watching.
     *
     * <p>Only sent when the search screen opens and closes. Without it the
     * server would either push to everyone - most of whom have no screen open -
     * or push to nobody.
     *
     * <p>These two shapes are defined here rather than in {@code core}'s
     * {@code QueryDto}: they carry no domain data, only connection bookkeeping,
     * and a future Paper plugin sharing {@code core} would have its own.
     */
    public record SubscribePayload(boolean watching) implements CustomPacketPayload {

        public static final Type<SubscribePayload> TYPE = new Type<>(id("subscribe"));

        public static final StreamCodec<FriendlyByteBuf, SubscribePayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> buf.writeBoolean(payload.watching()),
                        buf -> new SubscribePayload(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server tells a watching client that its view is out of date.
     *
     * <p>Carries no data on purpose. Sending the changed rows themselves would
     * be a second description of the index that can drift from the real one,
     * and would have to re-implement the filters and the permission tier to
     * know what this player is allowed to see. Instead the client re-asks
     * through the query path it already uses, which cannot disagree with
     * itself.
     */
    public record IndexChangedPayload() implements CustomPacketPayload {

        public static final Type<IndexChangedPayload> TYPE = new Type<>(id("index_changed"));

        public static final IndexChangedPayload INSTANCE = new IndexChangedPayload();

        public static final StreamCodec<FriendlyByteBuf, IndexChangedPayload> CODEC =
                StreamCodec.of((buf, payload) -> {}, buf -> INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // --- Hello --------------------------------------------------------------

    /** Server announces that it has an index, and whether this player may read it. */
    public record HelloPayload(QueryDto.Hello hello) implements CustomPacketPayload {

        public static final Type<HelloPayload> TYPE = new Type<>(id("hello"));

        public static final StreamCodec<FriendlyByteBuf, HelloPayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            buf.writeVarInt(payload.hello().protocolVersion());
                            buf.writeBoolean(payload.hello().canQuery());
                        },
                        buf -> new HelloPayload(new QueryDto.Hello(buf.readVarInt(), buf.readBoolean())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
