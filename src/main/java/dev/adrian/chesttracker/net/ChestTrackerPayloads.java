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
                        },
                        buf -> new SummaryRequestPayload(new QueryDto.SummaryRequest(
                                buf.readVarInt(), buf.readUtf(MAX_TEXT), readFilters(buf), buf.readVarInt())));

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
                            buf.writeVarInt(response.items().size());
                            for (QueryDto.ItemSummary item : response.items()) {
                                buf.writeUtf(item.itemId(), MAX_ID);
                                buf.writeVarInt(item.totalCount());
                                buf.writeVarInt(item.containerCount());
                                buf.writeDouble(item.nearestDistSq());
                            }
                        },
                        buf -> {
                            int requestId = buf.readVarInt();
                            return new SummaryResponsePayload(new QueryDto.SummaryResponse(requestId,
                                    readList(buf, b -> new QueryDto.ItemSummary(
                                            b.readUtf(MAX_ID), b.readVarInt(), b.readVarInt(), b.readDouble()))));
                        });

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
                        },
                        buf -> new ContainerRequestPayload(new QueryDto.ContainerRequest(
                                buf.readVarInt(), buf.readUtf(MAX_ID), readFilters(buf), buf.readVarInt())));

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
                            return new ContainerResponsePayload(new QueryDto.ContainerResponse(requestId,
                                    readList(buf, b -> new QueryDto.ContainerHit(
                                            b.readUtf(MAX_ID), b.readLong(), b.readVarInt(), b.readDouble(),
                                            b.readBoolean(), b.readBoolean(), b.readBoolean()))));
                        });

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
