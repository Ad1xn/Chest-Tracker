package dev.adrian.chesttracker.server;

import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.core.index.IndexQuery;
import dev.adrian.chesttracker.core.index.SearchResult;
import dev.adrian.chesttracker.core.index.WorldIndex;
import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.StackEntry;
import dev.adrian.chesttracker.core.store.IndexCodec;
import dev.adrian.chesttracker.core.store.StringPalette;
import dev.adrian.chesttracker.platform.ContainerTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the index for one world: a {@link WorldIndex} per dimension, the shared
 * string palette, and persistence.
 *
 * <p><b>Threading:</b> the index itself is not thread-safe, so every mutation
 * goes through this class on the server thread. Scanners may read region files
 * on background threads, but they hand finished records back here to apply.
 */
public final class TrackerService {

    private final StringPalette palette = new StringPalette();
    private final Map<String, WorldIndex> byDimension = new HashMap<>();
    private final ContainerTypes containerTypes = new ContainerTypes();
    private final Path storageRoot;

    public TrackerService(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public StringPalette palette() {
        return palette;
    }

    public ContainerTypes containerTypes() {
        return containerTypes;
    }

    /** The index for a dimension, created empty on first use. */
    public WorldIndex index(String dimensionId) {
        return byDimension.computeIfAbsent(dimensionId,
                id -> new WorldIndex(palette.intern(id)));
    }

    public Set<String> dimensions() {
        return Set.copyOf(byDimension.keySet());
    }

    public void record(String dimensionId, ContainerRecord container) {
        index(dimensionId).put(container);
    }

    public ContainerRecord remove(String dimensionId, long pos) {
        return index(dimensionId).remove(pos);
    }

    /**
     * Drops indexed containers a chunk no longer holds.
     *
     * <p>This is the backstop that makes removal reliable. A live block hook
     * only catches what happens while we are watching; anything broken while
     * the mod was off, the server ran without it, or the world was edited
     * externally is caught here instead, the next time the chunk is seen.
     *
     * @return how many stale records were dropped
     */
    public int reconcileChunk(String dimensionId, long chunkKey, Set<Long> actual) {
        return index(dimensionId).reconcileChunk(chunkKey, actual);
    }

    public List<SearchResult> search(String dimensionId, IndexQuery query) {
        WorldIndex index = byDimension.get(dimensionId);
        return index == null ? List.of() : index.query(query);
    }

    public int totalContainers() {
        int total = 0;
        for (WorldIndex index : byDimension.values()) total += index.size();
        return total;
    }

    // --- Persistence --------------------------------------------------------

    private Path fileFor(String dimensionId) {
        // Registry ids contain ':' and '/', neither of which belongs in a filename.
        String safe = dimensionId.replace(':', '.').replace('/', '.');
        return storageRoot.resolve(safe + ".idx");
    }

    public void load() {
        if (!Files.isDirectory(storageRoot)) return;
        try (var files = Files.list(storageRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".idx")).toList()) {
                try {
                    IndexCodec.Snapshot snapshot = IndexCodec.read(file);
                    String dimensionId = snapshot.palette().value(snapshot.index().dimensionId());
                    if (dimensionId == null) {
                        ChestTracker.LOG.warn("Skipping {}: its dimension id is missing from the palette", file);
                        continue;
                    }
                    // Re-intern through our palette: ids are file-local, and two
                    // files written at different times need not agree on them.
                    byDimension.put(dimensionId, reindex(snapshot, dimensionId));
                } catch (IOException e) {
                    // A corrupt index is recoverable - rescanning rebuilds it -
                    // so never let one bad file stop the world from loading.
                    ChestTracker.LOG.warn("Could not read index {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            ChestTracker.LOG.warn("Could not list index directory {}: {}", storageRoot, e.toString());
        }
    }

    /** Rewrites a loaded snapshot's palette ids into this service's palette. */
    private WorldIndex reindex(IndexCodec.Snapshot snapshot, String dimensionId) {
        WorldIndex target = new WorldIndex(palette.intern(dimensionId));
        for (ContainerRecord record : snapshot.index().all()) {
            ContainerRecord remapped = remap(record, snapshot.palette(), dimensionId);
            if (remapped != null) target.put(remapped);
        }
        return target;
    }

    /**
     * Translates a record built against a foreign palette into ours.
     *
     * <p>Needed in two places, for the same underlying reason: palette ids are
     * only meaningful next to the palette that produced them. A saved file has
     * its own, and so does a background scan - the scanner cannot intern into
     * the shared palette because {@link StringPalette} is not thread-safe and
     * the server thread is using it.
     *
     * @return the translated record, or null if the source palette was missing
     *         an id it referenced (a corrupt file, not a fatal condition)
     */
    public ContainerRecord remap(ContainerRecord record, StringPalette from, String dimensionId) {
        String typeId = from.value(record.typeId());
        if (typeId == null) return null;

        List<StackEntry> contents = new java.util.ArrayList<>(record.contents().size());
        for (StackEntry entry : record.contents()) {
            String itemId = from.value(entry.itemId());
            if (itemId == null) continue;
            contents.add(new StackEntry(palette.intern(itemId), entry.count(), entry.depth(), entry.customName()));
        }

        return new ContainerRecord(record.pos(), palette.intern(dimensionId), palette.intern(typeId),
                record.origin(), record.owner(), record.unlooted(), record.contentsKnown(),
                record.customName(), record.lastSeenTick(), contents);
    }

    public void save() {
        for (Map.Entry<String, WorldIndex> entry : byDimension.entrySet()) {
            try {
                IndexCodec.write(fileFor(entry.getKey()), palette, entry.getValue());
            } catch (IOException e) {
                ChestTracker.LOG.error("Could not save index for {}: {}", entry.getKey(), e.toString());
            }
        }
    }
}
