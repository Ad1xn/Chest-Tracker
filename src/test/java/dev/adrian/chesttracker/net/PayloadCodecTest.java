package dev.adrian.chesttracker.net;

import dev.adrian.chesttracker.core.net.QueryDto;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips every payload through a real buffer.
 *
 * <p>A codec whose writer and reader disagree about field order or width does
 * not fail loudly: it decodes the next field from the wrong bytes and produces
 * plausible nonsense, or overruns and drops the connection. Neither is visible
 * from reading the two halves side by side, which is why they are exercised
 * rather than reviewed.
 *
 * <p>No game state is involved - {@link FriendlyByteBuf} over a plain Netty
 * buffer is all the codecs touch.
 */
class PayloadCodecTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void summaryRequestSurvivesTheRoundTrip() {
        QueryDto.SummaryRequest original = new QueryDto.SummaryRequest(
                42, "redstone", new QueryDto.Filters(false, true, QueryDto.Filters.ORIGIN_NATURAL), 300);

        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.SummaryRequestPayload.CODEC.encode(
                buf, new ChestTrackerPayloads.SummaryRequestPayload(original));
        QueryDto.SummaryRequest decoded =
                ChestTrackerPayloads.SummaryRequestPayload.CODEC.decode(buf).request();

        assertEquals(original, decoded);
        assertEquals(0, buf.readableBytes(), "codec must consume exactly what it wrote");
    }

    @Test
    void summaryResponseSurvivesTheRoundTrip() {
        QueryDto.SummaryResponse original = QueryDto.SummaryResponse.of(7, List.of(
                new QueryDto.ItemSummary("minecraft:redstone", 2304, 4, 1728, 18.5),
                new QueryDto.ItemSummary("minecraft:diamond", 12, 1, 0, 400.0)));

        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.SummaryResponsePayload.CODEC.encode(
                buf, new ChestTrackerPayloads.SummaryResponsePayload(original));
        QueryDto.SummaryResponse decoded =
                ChestTrackerPayloads.SummaryResponsePayload.CODEC.decode(buf).response();

        assertEquals(original, decoded);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void containerRequestSurvivesTheRoundTrip() {
        QueryDto.ContainerRequest original = new QueryDto.ContainerRequest(
                3, "minecraft:redstone", QueryDto.Filters.defaults(), 64);

        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.ContainerRequestPayload.CODEC.encode(
                buf, new ChestTrackerPayloads.ContainerRequestPayload(original));

        assertEquals(original, ChestTrackerPayloads.ContainerRequestPayload.CODEC.decode(buf).request());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void containerResponseKeepsEveryFlagDistinct() {
        // Three booleans in a row is exactly where a transposed field goes
        // unnoticed, so they are given three different values.
        QueryDto.ContainerResponse original = QueryDto.ContainerResponse.of(9, List.of(
                new QueryDto.ContainerHit("minecraft:chest", -1234567890123L, 64, 91.25, true, false, true),
                new QueryDto.ContainerHit("minecraft:barrel", 42L, 1, 0.0, false, true, false)));

        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.ContainerResponsePayload.CODEC.encode(
                buf, new ChestTrackerPayloads.ContainerResponsePayload(original));
        QueryDto.ContainerResponse decoded =
                ChestTrackerPayloads.ContainerResponsePayload.CODEC.decode(buf).response();

        assertEquals(original, decoded);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void helloSurvivesTheRoundTrip() {
        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.HelloPayload.CODEC.encode(buf,
                new ChestTrackerPayloads.HelloPayload(new QueryDto.Hello(QueryDto.Hello.PROTOCOL_VERSION, true)));
        QueryDto.Hello decoded = ChestTrackerPayloads.HelloPayload.CODEC.decode(buf).hello();

        assertEquals(QueryDto.Hello.PROTOCOL_VERSION, decoded.protocolVersion());
        assertTrue(decoded.canQuery());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void emptyResponsesRoundTrip() {
        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.SummaryResponsePayload.CODEC.encode(buf,
                new ChestTrackerPayloads.SummaryResponsePayload(QueryDto.SummaryResponse.of(1, List.of())));

        assertTrue(ChestTrackerPayloads.SummaryResponsePayload.CODEC.decode(buf).response().items().isEmpty());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void subscribeSurvivesTheRoundTripBothWays() {
        for (boolean watching : new boolean[]{true, false}) {
            FriendlyByteBuf buf = buffer();
            ChestTrackerPayloads.SubscribePayload.CODEC.encode(
                    buf, new ChestTrackerPayloads.SubscribePayload(watching));

            assertEquals(watching,
                    ChestTrackerPayloads.SubscribePayload.CODEC.decode(buf).watching());
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void theChangeSignalCarriesNoBytes() {
        // It is sent per watcher on a timer, so an empty body is the point.
        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.IndexChangedPayload.CODEC.encode(
                buf, ChestTrackerPayloads.IndexChangedPayload.INSTANCE);

        assertEquals(0, buf.readableBytes());
        assertNotNull(ChestTrackerPayloads.IndexChangedPayload.CODEC.decode(buf));
    }

    @Test
    void aRefusalSurvivesTheRoundTrip() {
        // The flag rides on every reply, so opping someone mid-session takes
        // effect on their next query rather than on their next login.
        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.SummaryResponsePayload.CODEC.encode(buf,
                new ChestTrackerPayloads.SummaryResponsePayload(QueryDto.SummaryResponse.refused(4)));
        QueryDto.SummaryResponse decoded =
                ChestTrackerPayloads.SummaryResponsePayload.CODEC.decode(buf).response();

        assertFalse(decoded.permitted());
        assertEquals(4, decoded.requestId());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void anImplausibleEntryCountIsRefusedRatherThanAllocatedFor() {
        // A hostile or corrupt packet can declare any length it likes. The read
        // must refuse before it reserves room for it, or one small packet asks
        // the other side for an arbitrarily large allocation.
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);              // request id
        buf.writeBoolean(true);          // permitted
        buf.writeVarInt(Integer.MAX_VALUE); // claimed entry count

        assertThrows(IllegalArgumentException.class,
                () -> ChestTrackerPayloads.SummaryResponsePayload.CODEC.decode(buf));
    }

    @Test
    void statusResponseSurvivesTheRoundTrip() {
        QueryDto.StatusResponse original = new QueryDto.StatusResponse(11, true, 3, 40, 512, List.of(
                new QueryDto.DimensionSummary("minecraft:overworld", 120),
                new QueryDto.DimensionSummary("minecraft:the_nether", 4)));

        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.StatusResponsePayload.CODEC.encode(
                buf, new ChestTrackerPayloads.StatusResponsePayload(original));
        QueryDto.StatusResponse decoded =
                ChestTrackerPayloads.StatusResponsePayload.CODEC.decode(buf).response();

        assertEquals(original, decoded);
        assertEquals(0, buf.readableBytes(), "codec must consume exactly what it wrote");
    }

    @Test
    void statusProgressIsZeroUntilTheTotalIsKnown() {
        // The scanner counts regions against a total it only learns after
        // walking the directory, so a bar driven by this must not divide by it.
        assertEquals(0.0f, new QueryDto.StatusResponse(1, true, 5, 0, 90, List.of()).progress());
        assertEquals(0.5f, new QueryDto.StatusResponse(1, true, 5, 10, 90, List.of()).progress());
    }

    @Test
    void aRequestCarriesTheDimensionItMeans() {
        QueryDto.ContainerRequest original = new QueryDto.ContainerRequest(
                4, "minecraft:diamond", QueryDto.Filters.defaults(), 8, "minecraft:the_end");

        FriendlyByteBuf buf = buffer();
        ChestTrackerPayloads.ContainerRequestPayload.CODEC.encode(
                buf, new ChestTrackerPayloads.ContainerRequestPayload(original));

        assertEquals(original, ChestTrackerPayloads.ContainerRequestPayload.CODEC.decode(buf).request());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void anOutOfRangeOriginOffTheWireDecodesToAnyRatherThanThrowing() {
        FriendlyByteBuf buf = buffer();
        buf.writeVarInt(1);        // request id
        buf.writeUtf("");          // text
        buf.writeBoolean(true);    // includeNested
        buf.writeBoolean(false);   // includeMachines
        buf.writeVarInt(9999);     // origin filter, not a value we ever send
        buf.writeVarInt(10);       // limit
        buf.writeUtf("");          // dimension, blank meaning "where I am"

        QueryDto.SummaryRequest decoded =
                ChestTrackerPayloads.SummaryRequestPayload.CODEC.decode(buf).request();

        assertEquals(QueryDto.Filters.ORIGIN_ANY, decoded.filters().originFilter());
        assertEquals("", decoded.dimensionId());
        assertEquals(0, buf.readableBytes());
    }
}
