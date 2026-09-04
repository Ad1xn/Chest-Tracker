package dev.adrian.chesttracker.core.model;

import dev.adrian.chesttracker.core.util.BlockKey;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the index knows about one container.
 *
 * <p>Registry names are stored as palette ids rather than strings: a large world
 * holds hundreds of thousands of stacks drawn from a few thousand distinct
 * items, so an int per stack instead of a string is what keeps the index in
 * memory. See {@link dev.adrian.chesttracker.core.store.StringPalette}.
 *
 * @param pos           packed via {@link BlockKey}, not Minecraft's own layout
 * @param dimensionId   palette id of the dimension's registry name
 * @param typeId        palette id of the container's block/type registry name
 * @param origin        natural, player-placed, or genuinely unknown
 * @param owner         who placed it, when that was observed; otherwise null
 * @param unlooted      still holds an unrolled loot table, i.e. generated and
 *                      never opened
 * @param contentsKnown false for a location-only entry, which is all a client
 *                      can learn on a vanilla server without opening the
 *                      container. Such a record must never be rendered as an
 *                      empty container
 * @param customName    the container's own name if it has one, as plain text
 * @param lastSeenTick  when contents were last confirmed. Entries in unloaded
 *                      chunks cannot be re-verified without loading them, so
 *                      the UI shows this as staleness rather than implying
 *                      the data is current
 * @param contents      flattened stacks, immutable
 */
public record ContainerRecord(
        long pos,
        int dimensionId,
        int typeId,
        Origin origin,
        UUID owner,
        boolean unlooted,
        boolean contentsKnown,
        String customName,
        long lastSeenTick,
        List<StackEntry> contents
) {

    public ContainerRecord {
        Objects.requireNonNull(origin, "origin");
        contents = contents == null ? List.of() : List.copyOf(contents);
        if (!contentsKnown && !contents.isEmpty()) {
            throw new IllegalArgumentException("contentsKnown=false but " + contents.size() + " stacks supplied");
        }
    }

    /** A location-only record: we know a container is here, not what is in it. */
    public static ContainerRecord locationOnly(long pos, int dimensionId, int typeId, Origin origin, long tick) {
        return new ContainerRecord(pos, dimensionId, typeId, origin, null, false, false, null, tick, List.of());
    }

    /**
     * Whether this record says the same thing as {@code other}, ignoring when
     * it was last seen.
     *
     * <p>Used to tell a real change from a re-read. Containers are re-read
     * constantly - every query refreshes whatever is loaded - and each re-read
     * writes a fresh {@code lastSeenTick}, so plain equality would call every
     * one of them a change. Anything driven off "did this change" would then
     * fire continuously, and a change signal that is always on carries no
     * information.
     */
    public boolean sameDataAs(ContainerRecord other) {
        return other != null
                && pos == other.pos
                && dimensionId == other.dimensionId
                && typeId == other.typeId
                && origin == other.origin
                && unlooted == other.unlooted
                && contentsKnown == other.contentsKnown
                && Objects.equals(owner, other.owner)
                && Objects.equals(customName, other.customName)
                && contents.equals(other.contents);
    }

    public long chunkKey() {
        return BlockKey.chunkOf(pos);
    }

    public boolean isEmpty() {
        return contentsKnown && contents.isEmpty();
    }

    /** Total item count across every stack, nested ones included. */
    public int totalItems() {
        int total = 0;
        for (StackEntry entry : contents) total += entry.count();
        return total;
    }

    public boolean containsItem(int itemId) {
        for (StackEntry entry : contents) {
            if (entry.itemId() == itemId) return true;
        }
        return false;
    }

    /**
     * Returns this record with contents replaced and {@code contentsKnown} set,
     * preserving the classification we already established.
     */
    public ContainerRecord withContents(List<StackEntry> newContents, String newCustomName, long tick) {
        return new ContainerRecord(pos, dimensionId, typeId, origin, owner, unlooted, true, newCustomName, tick, newContents);
    }

    public ContainerRecord withOrigin(Origin newOrigin, UUID newOwner) {
        return new ContainerRecord(pos, dimensionId, typeId, origin.merge(newOrigin),
                newOwner != null ? newOwner : owner, unlooted, contentsKnown, customName, lastSeenTick, contents);
    }

    public ContainerRecord withUnlooted(boolean nowUnlooted) {
        return new ContainerRecord(pos, dimensionId, typeId, origin, owner, nowUnlooted, contentsKnown, customName, lastSeenTick, contents);
    }
}
