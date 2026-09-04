package dev.adrian.chesttracker.core.index;

import dev.adrian.chesttracker.core.model.Origin;

import java.util.Set;

/**
 * A search against a {@link WorldIndex}. Empty filter sets mean "no constraint"
 * rather than "match nothing", so a default query returns everything.
 *
 * @param itemIds           palette ids to look for; empty matches any item
 * @param origins           natural / player-placed / unknown; empty matches any
 * @param typeIds           container type palette ids; empty matches any
 * @param excludedTypeIds   container types to leave out, applied after
 *                          {@code typeIds}. Inclusive filtering cannot express
 *                          "everything except machines" without listing every
 *                          other type, which the caller has no reliable way to
 *                          enumerate
 * @param unlootedOnly      only generated containers nobody has opened
 * @param knownContentsOnly drop location-only entries whose contents we cannot
 *                          know (the vanilla-server case)
 * @param includeNested     whether a hit inside a shulker box counts
 * @param center            packed origin for distance ranking
 * @param maxDistance       in blocks; zero or less means unlimited
 * @param limit             maximum results; zero or less means unlimited
 */
public record IndexQuery(
        Set<Integer> itemIds,
        Set<Origin> origins,
        Set<Integer> typeIds,
        Set<Integer> excludedTypeIds,
        boolean unlootedOnly,
        boolean knownContentsOnly,
        boolean includeNested,
        long center,
        double maxDistance,
        int limit
) {

    public IndexQuery {
        itemIds = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        origins = origins == null ? Set.of() : Set.copyOf(origins);
        typeIds = typeIds == null ? Set.of() : Set.copyOf(typeIds);
        excludedTypeIds = excludedTypeIds == null ? Set.of() : Set.copyOf(excludedTypeIds);
    }

    public boolean hasDistanceLimit() {
        return maxDistance > 0;
    }

    public double maxDistanceSq() {
        return maxDistance * maxDistance;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Set<Integer> itemIds = Set.of();
        private Set<Origin> origins = Set.of();
        private Set<Integer> typeIds = Set.of();
        private Set<Integer> excludedTypeIds = Set.of();
        private boolean unlootedOnly;
        private boolean knownContentsOnly;
        private boolean includeNested = true;
        private long center;
        private double maxDistance;
        private int limit;

        public Builder items(Set<Integer> ids) { this.itemIds = ids; return this; }
        public Builder item(int id) { this.itemIds = Set.of(id); return this; }
        public Builder origins(Set<Origin> o) { this.origins = o; return this; }
        public Builder origin(Origin o) { this.origins = Set.of(o); return this; }
        public Builder types(Set<Integer> ids) { this.typeIds = ids; return this; }
        public Builder excludeTypes(Set<Integer> ids) { this.excludedTypeIds = ids; return this; }
        public Builder unlootedOnly(boolean v) { this.unlootedOnly = v; return this; }
        public Builder knownContentsOnly(boolean v) { this.knownContentsOnly = v; return this; }
        public Builder includeNested(boolean v) { this.includeNested = v; return this; }
        public Builder center(long packedPos) { this.center = packedPos; return this; }
        public Builder maxDistance(double blocks) { this.maxDistance = blocks; return this; }
        public Builder limit(int n) { this.limit = n; return this; }

        public IndexQuery build() {
            return new IndexQuery(itemIds, origins, typeIds, excludedTypeIds, unlootedOnly,
                    knownContentsOnly, includeNested, center, maxDistance, limit);
        }
    }
}
