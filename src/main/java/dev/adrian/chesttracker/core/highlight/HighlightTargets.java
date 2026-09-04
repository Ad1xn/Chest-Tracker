package dev.adrian.chesttracker.core.highlight;

import dev.adrian.chesttracker.core.util.BlockKey;

import java.util.List;

/**
 * Choosing which of several highlighted containers to point at.
 *
 * <p>A search answers with every container holding the item, but an action bar
 * has room for one bearing, so one of them has to be picked - and picked again
 * as the player moves, or guidance marches them past a closer one on the way to
 * whichever happened to be nearest when they pressed the key.
 *
 * <p>Here rather than in the client class for the usual reason: it is
 * arithmetic, and arithmetic that decides what a player is told is worth
 * testing rather than eyeballing in game.
 */
public final class HighlightTargets {

    private HighlightTargets() {}

    /**
     * The target closest to a point.
     *
     * @return the packed position of the nearest target, or 0 when there are
     *         none - callers are expected to check for an empty selection
     *         first, and 0 is a legal position rather than a sentinel
     */
    public static long nearest(List<Long> targets, int x, int y, int z) {
        if (targets == null || targets.isEmpty()) return 0L;

        long from = BlockKey.pack(x, y, z);
        long best = targets.get(0);
        double bestDistance = BlockKey.distanceSq(from, best);

        for (int i = 1; i < targets.size(); i++) {
            long candidate = targets.get(i);
            double distance = BlockKey.distanceSq(from, candidate);
            // Strictly closer, so an exact tie keeps the earlier one and the
            // choice does not flicker between two equidistant containers.
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
