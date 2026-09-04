package dev.adrian.chesttracker.core.net;

import dev.adrian.chesttracker.core.model.Origin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryDtoTest {

    @Test
    void originFilterMapsToTheMatchingOrigin() {
        assertEquals(Set.of(Origin.PLAYER_PLACED),
                new QueryDto.Filters(true, false, QueryDto.Filters.ORIGIN_PLAYER_PLACED).origins());
        assertEquals(Set.of(Origin.NATURAL),
                new QueryDto.Filters(true, false, QueryDto.Filters.ORIGIN_NATURAL).origins());
    }

    @Test
    void anyOriginMeansNoConstraint() {
        // Empty is "no filter", not "match nothing" - the index reads it that way.
        assertTrue(new QueryDto.Filters(true, false, QueryDto.Filters.ORIGIN_ANY).origins().isEmpty());
    }

    @Test
    void outOfRangeOriginFallsBackToAny() {
        // These arrive off the wire, so the values are whatever the other side
        // chose to send. An unrecognised one must not select a different filter
        // by accident, and must not throw on a packet either.
        assertEquals(QueryDto.Filters.ORIGIN_ANY, new QueryDto.Filters(true, false, 99).originFilter());
        assertEquals(QueryDto.Filters.ORIGIN_ANY, new QueryDto.Filters(true, false, -3).originFilter());
        assertTrue(new QueryDto.Filters(true, false, 99).origins().isEmpty());
    }

    @Test
    void refusalAndAnswerAreDistinguishable() {
        // An empty answer and a refusal look the same in the list, so the flag
        // is the only thing that tells the screen which message to show - and
        // it is what lets a mid-session op take effect without reconnecting.
        assertFalse(QueryDto.SummaryResponse.refused(1).permitted());
        assertTrue(QueryDto.SummaryResponse.of(1, List.of()).permitted());
        assertFalse(QueryDto.ContainerResponse.refused(1).permitted());
        assertTrue(QueryDto.ContainerResponse.of(1, List.of()).permitted());
    }

    @Test
    void defaultsCountNestedItemsAndHideMachines() {
        QueryDto.Filters defaults = QueryDto.Filters.defaults();
        assertTrue(defaults.includeNested());
        assertFalse(defaults.includeMachines());
        assertEquals(QueryDto.Filters.ORIGIN_ANY, defaults.originFilter());
    }

    @Test
    void responsesCopyTheirListsAndTolerateNull() {
        List<QueryDto.ItemSummary> source = new java.util.ArrayList<>();
        source.add(new QueryDto.ItemSummary("minecraft:redstone", 64, 1, 4.0));
        QueryDto.SummaryResponse response = QueryDto.SummaryResponse.of(7, source);

        source.clear();
        assertEquals(1, response.items().size(), "response must not alias the caller's list");
        assertEquals(7, response.requestId());

        assertTrue(QueryDto.SummaryResponse.of(1, null).items().isEmpty());
        assertTrue(QueryDto.ContainerResponse.of(1, null).hits().isEmpty());
    }
}
