package dev.adrian.chesttracker.core.net;

import dev.adrian.chesttracker.core.model.Origin;

import java.util.List;
import java.util.Set;

/**
 * The shapes that cross between client and server.
 *
 * <p>Everything here names items and container types by their <b>registry
 * string</b>, never by a palette id. Palette ids are local to whichever palette
 * produced them, so the same int means different things on either side of the
 * connection; sending them would appear to work and silently mislabel
 * everything.
 *
 * <p>Singleplayer produces these too, rather than shortcutting to the index
 * directly. One shape means the screen has a single code path whether or not a
 * network is involved, and the singleplayer path is not a special case that can
 * rot while nobody is looking.
 */
public final class QueryDto {

    private QueryDto() {}

    /** What the toolbar buttons select. */
    public record Filters(boolean includeNested, boolean includeMachines, int originFilter) {

        public static final int ORIGIN_ANY = 0;
        public static final int ORIGIN_PLAYER_PLACED = 1;
        public static final int ORIGIN_NATURAL = 2;

        public static Filters defaults() {
            return new Filters(true, false, ORIGIN_ANY);
        }

        public Set<Origin> origins() {
            return switch (originFilter) {
                case ORIGIN_PLAYER_PLACED -> Set.of(Origin.PLAYER_PLACED);
                case ORIGIN_NATURAL -> Set.of(Origin.NATURAL);
                default -> Set.of();
            };
        }
    }

    /**
     * Ask for item totals.
     *
     * @param text free text matched against item ids; blank means everything
     */
    public record SummaryRequest(String text, Filters filters, long centre, int limit) {}

    /**
     * One item, totalled across the containers holding it.
     *
     * @param itemId registry name, e.g. {@code minecraft:redstone}
     */
    public record ItemSummary(String itemId, int totalCount, int containerCount, double nearestDistSq) {}

    public record SummaryResponse(List<ItemSummary> items) {
        public SummaryResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** Ask where one item is. */
    public record ContainerRequest(String itemId, Filters filters, long centre, int limit) {}

    /**
     * One container holding the requested item.
     *
     * @param contentsKnown false when the container's contents cannot be known,
     *                      so the UI can say so rather than imply it is empty
     */
    public record ContainerHit(String typeId, long pos, int matchedCount, double distanceSq,
                               boolean nested, boolean natural, boolean contentsKnown) {}

    public record ContainerResponse(List<ContainerHit> hits) {
        public ContainerResponse {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }
}
