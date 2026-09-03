package dev.adrian.chesttracker.core.model;

/**
 * One item stack found inside a container, flattened out of any nesting.
 *
 * @param itemId     palette id of the item's registry name
 * @param count      how many
 * @param depth      0 for a stack sitting directly in the container, 1 for one
 *                   inside a shulker box in that container, and so on
 * @param customName anvil-renamed display name as plain text, or null. Kept so
 *                   searching for "spare pickaxe" works; rendering the real
 *                   styled name is the UI's job
 */
public record StackEntry(int itemId, int count, int depth, String customName) {

    public StackEntry {
        if (count < 0) throw new IllegalArgumentException("count must not be negative: " + count);
        if (depth < 0) throw new IllegalArgumentException("depth must not be negative: " + depth);
    }

    public StackEntry(int itemId, int count) {
        this(itemId, count, 0, null);
    }

    public StackEntry(int itemId, int count, int depth) {
        this(itemId, count, depth, null);
    }

    /** True if this stack is inside another container rather than loose in this one. */
    public boolean isNested() {
        return depth > 0;
    }
}
