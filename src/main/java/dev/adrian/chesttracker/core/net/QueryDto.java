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
 *
 * <p>Neither request carries a position or a dimension, deliberately. The
 * server already knows where the asking player is, so sending it would add a
 * value the server must either trust or ignore - and a client that could move
 * the query centre could rank a search around somewhere it has never been.
 */
public final class QueryDto {

    private QueryDto() {}

    /** What the toolbar buttons select. */
    public record Filters(boolean includeNested, boolean includeMachines, int originFilter) {

        public static final int ORIGIN_ANY = 0;
        public static final int ORIGIN_PLAYER_PLACED = 1;
        public static final int ORIGIN_NATURAL = 2;

        public Filters {
            // A byte off the wire could name an origin that does not exist.
            if (originFilter < ORIGIN_ANY || originFilter > ORIGIN_NATURAL) originFilter = ORIGIN_ANY;
        }

        public static Filters defaults() {
            return new Filters(true, false, ORIGIN_ANY);
        }

        /**
         * What the origin filter actually selects.
         *
         * <p>"Player-built" deliberately includes {@link Origin#UNKNOWN}, and
         * this matters more than it looks. Placement can only be observed as it
         * happens, so on a world that existed before the mod was installed
         * <em>every</em> chest a player ever built is {@code UNKNOWN} - and a
         * filter that took the label literally would show them none of their
         * own base and tell them to go and re-place every chest they own.
         *
         * <p>The reverse reading is sound: generated containers are positively
         * identified, by a structure piece or an unrolled loot table. Anything
         * that is not generated and is standing in the world was put there by
         * somebody. So the filter is really "generated or not", and the
         * uncertain case belongs on the side that does not lose the player
         * their own storage.
         */
        public Set<Origin> origins() {
            return switch (originFilter) {
                case ORIGIN_PLAYER_PLACED -> Set.of(Origin.PLAYER_PLACED, Origin.UNKNOWN);
                case ORIGIN_NATURAL -> Set.of(Origin.NATURAL);
                default -> Set.of();
            };
        }
    }

    /**
     * Ask for item totals.
     *
     * <p>The id is echoed in the reply. Every keystroke starts a query, replies
     * need not arrive in the order they were asked for, and without this a slow
     * early reply lands after a fast later one and shows results for a search
     * the player has already moved on from.
     *
     * @param requestId caller's correlation id
     * @param text      free text matched against item ids; blank means everything
     */
    public record SummaryRequest(int requestId, String text, Filters filters, int limit) {}

    /**
     * One item, totalled across the containers holding it.
     *
     * @param itemId registry name, e.g. {@code minecraft:redstone}
     */
    /**
     * @param nestedCount how many of {@code totalCount} are inside a shulker
     *                    box rather than loose. Sent because the screen cannot
     *                    work it out: a slot showing 900 wool looks identical
     *                    whether it is stacked in a barrel or sealed inside
     *                    nine shulkers, and those are different answers to
     *                    "where is my wool".
     */
    public record ItemSummary(String itemId, int totalCount, int containerCount,
                              int nestedCount, double nearestDistSq) {}

    /**
     * @param permitted whether the server answered this at all. Carried on
     *                  every reply, not just the greeting: permission can
     *                  change while a player is connected - being opped is the
     *                  obvious case - and a greeting sent once at join would
     *                  leave the client believing the old answer until it
     *                  reconnected.
     */
    public record SummaryResponse(int requestId, boolean permitted, List<ItemSummary> items) {
        public SummaryResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static SummaryResponse refused(int requestId) {
            return new SummaryResponse(requestId, false, List.of());
        }

        public static SummaryResponse of(int requestId, List<ItemSummary> items) {
            return new SummaryResponse(requestId, true, items);
        }
    }

    /** Ask where one item is. */
    public record ContainerRequest(int requestId, String itemId, Filters filters, int limit) {}

    /**
     * One container holding the requested item.
     *
     * @param contentsKnown false when the container's contents cannot be known,
     *                      so the UI can say so rather than imply it is empty
     */
    public record ContainerHit(String typeId, long pos, int matchedCount, double distanceSq,
                               boolean nested, boolean natural, boolean contentsKnown) {}

    /** @param permitted see {@link SummaryResponse#permitted()} */
    public record ContainerResponse(int requestId, boolean permitted, List<ContainerHit> hits) {
        public ContainerResponse {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        public static ContainerResponse refused(int requestId) {
            return new ContainerResponse(requestId, false, List.of());
        }

        public static ContainerResponse of(int requestId, List<ContainerHit> hits) {
            return new ContainerResponse(requestId, true, hits);
        }
    }

    /**
     * Sent unprompted by a server that has the mod, once the player is in.
     *
     * <p>Custom payloads are silently dropped by a server that does not know
     * them, so a client cannot learn by asking - a vanilla server's reply to a
     * query is indistinguishable from a slow one. Announcing instead means the
     * client waits a short grace period and then knows.
     *
     * @param canQuery whether this player's tier allows a query right now. Only
     *                 an opening position - permission can change mid-session,
     *                 so every reply carries it too and the client believes the
     *                 most recent one
     */
    public record Hello(int protocolVersion, boolean canQuery) {

        /**
         * Bumped when the payload shapes change incompatibly.
         *
         * <p>3 added a nested count to every item summary. Two peers that
         * disagree about a payload's shape while both claiming the same
         * version do not fail, they desync - the reader takes the next field
         * from the middle of the previous one - so this has to move whenever a
         * field does.
         */
        public static final int PROTOCOL_VERSION = 3;
    }
}
