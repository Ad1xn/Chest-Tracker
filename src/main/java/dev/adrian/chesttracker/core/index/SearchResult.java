package dev.adrian.chesttracker.core.index;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.StackEntry;

import java.util.List;

/**
 * One container that satisfied a query.
 *
 * @param container the matching record
 * @param distanceSq squared distance from the query centre, kept squared so
 *                   ranking never pays for a square root
 * @param matches    the stacks that actually matched, empty when the query had
 *                   no item filter
 */
public record SearchResult(ContainerRecord container, double distanceSq, List<StackEntry> matches) {

    public SearchResult {
        matches = matches == null ? List.of() : List.copyOf(matches);
    }

    public double distance() {
        return Math.sqrt(distanceSq);
    }

    /** Total count of matching items, for "you have 384 diamonds" style summaries. */
    public int matchedCount() {
        int total = 0;
        for (StackEntry entry : matches) total += entry.count();
        return total;
    }
}
