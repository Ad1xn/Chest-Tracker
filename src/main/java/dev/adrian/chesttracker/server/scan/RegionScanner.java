package dev.adrian.chesttracker.server.scan;

import dev.adrian.chesttracker.ChestTracker;
import dev.adrian.chesttracker.core.store.ScanLog;
import dev.adrian.chesttracker.core.anvil.ChunkExtractor;
import dev.adrian.chesttracker.core.anvil.NbtCompound;
import dev.adrian.chesttracker.core.anvil.OriginClassifier;
import dev.adrian.chesttracker.core.anvil.RegionFile;
import dev.adrian.chesttracker.core.anvil.WorldLayout;
import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.store.StringPalette;
import dev.adrian.chesttracker.core.util.BlockKey;
import dev.adrian.chesttracker.server.TrackerService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Indexes containers by reading region files straight off disk, without asking
 * the game to load a single chunk.
 *
 * <p>This is the feature that makes "index the whole world" mean the whole
 * world. A chest's serialised block entity carries both its {@code Items} and
 * its unrolled {@code LootTable}, so one pass yields contents and the
 * natural/unlooted signal together, for terrain nobody is standing in.
 *
 * <h2>Threading</h2>
 * File reading and NBT parsing happen on a background thread; nothing is
 * applied there. Results cross to the server thread as {@link Batch}es and are
 * applied under a per-tick budget.
 *
 * <p>The scan therefore uses its <b>own</b> {@link StringPalette}: interning
 * into the shared one off-thread would race the server. Batches are translated
 * on arrival by {@link TrackerService#remap}.
 *
 * <h2>Loaded chunks are skipped</h2>
 * A loaded chunk may hold unsaved changes, which would make the on-disk copy
 * stale. Those are left to {@link LiveScanner}, whose data is authoritative.
 * The check happens on the server thread, where asking is safe.
 */
public final class RegionScanner {

    /** Chunks applied to the index per server tick. Keeps the apply step off the frame budget. */
    private static final int APPLY_BUDGET_PER_TICK = 64;

    /** Pause the reader when the server is struggling; nothing here is urgent. */
    private static final long TICK_TIME_BUDGET_NANOS = 45_000_000L; // 45ms of a 50ms tick

    private final TrackerService tracker;
    private final Queue<Batch> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private final AtomicInteger chunksRead = new AtomicInteger();
    private final AtomicInteger regionsRead = new AtomicInteger();
    private final AtomicInteger regionsTotal = new AtomicInteger();
    private final AtomicInteger containersFound = new AtomicInteger();
    private final AtomicInteger chunksSkippedLoaded = new AtomicInteger();
    private final AtomicInteger chunksFailed = new AtomicInteger();

    private volatile Thread worker;

    public RegionScanner(TrackerService tracker) {
        this.tracker = tracker;
    }

    /** One chunk's worth of results, waiting to be applied on the server thread. */
    private record Batch(String dimensionId, long chunkKey, List<ContainerRecord> containers, StringPalette palette) {}

    /** Live progress, safe to read from any thread. */
    public record Progress(boolean running, int regionsRead, int regionsTotal, int chunksRead,
                           int containersFound, int chunksSkippedLoaded, int chunksFailed) {
        public boolean isComplete() {
            return !running && regionsTotal > 0 && regionsRead >= regionsTotal;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Progress progress() {
        return new Progress(running.get(), regionsRead.get(), regionsTotal.get(), chunksRead.get(),
                containersFound.get(), chunksSkippedLoaded.get(), chunksFailed.get());
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void writeLog(ScanLog log, Path file) {
        try {
            log.write(file);
        } catch (IOException e) {
            // Losing the log costs a repeated scan, never correctness.
            ChestTracker.LOG.warn("Could not write the scan log {}: {}", file, e.toString());
        }
    }

    /** Forgets every region, so the next scan reads the world in full. */
    public void forgetScanned(Path logFile) {
        ScanLog log = new ScanLog();
        writeLog(log, logFile);
    }

    public void cancel() {
        cancelled.set(true);
        Thread current = worker;
        if (current != null) current.interrupt();
    }

    /**
     * Starts a background scan of every dimension under {@code worldRoot}.
     *
     * @return false if a scan is already running
     */
    public boolean start(Path worldRoot, long tick) {
        if (!running.compareAndSet(false, true)) return false;

        cancelled.set(false);
        chunksRead.set(0);
        regionsRead.set(0);
        regionsTotal.set(0);
        containersFound.set(0);
        chunksSkippedLoaded.set(0);
        chunksFailed.set(0);

        Thread thread = new Thread(() -> {
            try {
                scanWorld(worldRoot, tick);
            } catch (Throwable t) {
                ChestTracker.LOG.error("Region scan failed", t);
            } finally {
                running.set(false);
                worker = null;
            }
        }, "ChestTracker-RegionScan");
        // A daemon thread so a half-finished scan can never hold the game open.
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        worker = thread;
        thread.start();
        return true;
    }

    /**
     * How often the log is written during a scan.
     *
     * <p>Often enough that killing the game loses seconds of work rather than
     * minutes, rarely enough that a scan is not dominated by writing a file.
     */
    private static final int LOG_EVERY = 25;

    private void scanWorld(Path worldRoot, long tick) throws IOException {
        List<WorldLayout.DimensionRegions> dimensions = WorldLayout.discover(worldRoot);
        if (dimensions.isEmpty()) {
            ChestTracker.LOG.warn("No region directories found under {}", worldRoot);
            return;
        }

        List<Path> allRegions = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        for (WorldLayout.DimensionRegions dimension : dimensions) {
            for (Path file : WorldLayout.regionFiles(dimension.regionDir())) {
                allRegions.add(file);
                owners.add(dimension.dimensionId());
            }
        }
        regionsTotal.set(allRegions.size());

        // Everything already read and unchanged since. Playing rewrites the
        // regions the player was in, so those come back; the rest of the world
        // does not have to be read a second time.
        Path logFile = tracker.scanLogFile();
        ScanLog log = ScanLog.read(logFile);

        List<Path> pending = new ArrayList<>();
        List<String> pendingOwners = new ArrayList<>();
        for (int i = 0; i < allRegions.size(); i++) {
            Path file = allRegions.get(i);
            if (log.isCurrent(file.toString(), sizeOf(file), modifiedAt(file))) {
                // Counted as read, so the progress bar measures the world and
                // not the leftovers - a resumed scan should look nearly done.
                regionsRead.incrementAndGet();
                continue;
            }
            pending.add(file);
            pendingOwners.add(owners.get(i));
        }

        ChestTracker.LOG.info("Region scan starting: {} of {} region files need reading "
                        + "across {} dimension(s)",
                pending.size(), allRegions.size(), dimensions.size());

        Set<String> trackedIds = tracker.containerTypes().known();

        int sinceWrite = 0;
        for (int i = 0; i < pending.size() && !cancelled.get(); i++) {
            Path file = pending.get(i);
            // Read before the scan, not after: if the file changes while being
            // read, marking it with the older stamp means it is read again next
            // time rather than being wrongly believed current.
            long size = sizeOf(file);
            long modifiedAt = modifiedAt(file);

            scanRegion(file, pendingOwners.get(i), trackedIds, tick);
            regionsRead.incrementAndGet();

            log.mark(file.toString(), size, modifiedAt);
            if (++sinceWrite >= LOG_EVERY) {
                sinceWrite = 0;
                writeLog(log, logFile);
            }
        }

        // Written on cancellation too. That is the whole point: what was read
        // before quitting stays read, so a world too big to scan in one sitting
        // finishes over several.
        writeLog(log, logFile);

        ChestTracker.LOG.info("Region scan {}: {} chunks, {} containers, {} failed, "
                        + "{} regions skipped as unchanged",
                cancelled.get() ? "cancelled" : "complete",
                chunksRead.get(), containersFound.get(), chunksFailed.get(),
                allRegions.size() - pending.size());
    }

    /**
     * Scans one region file, buffering results until the whole region is read.
     *
     * <p>The buffering is what makes structure classification correct. A chest's
     * own chunk usually records only a structure <i>reference</i>; the bounding
     * box lives in whichever chunk the structure started in, which may be read
     * later. Classifying per chunk would therefore miss most structure chests.
     * Holding one region's containers costs little and lets every box in that
     * region apply to every container in it.
     */
    private void scanRegion(Path file, String dimensionId, Set<String> trackedIds, long tick) {
        int[] region = RegionFile.parseCoords(file.getFileName().toString());
        if (region == null) return;

        // One palette per region rather than per chunk, still confined to this thread.
        StringPalette localPalette = new StringPalette();
        int localDimensionId = localPalette.intern(dimensionId);

        Map<Long, List<ContainerRecord>> byChunk = new LinkedHashMap<>();
        List<ChunkExtractor.StructureBox> regionBoxes = new ArrayList<>();

        try (RegionFile regionFile = RegionFile.open(file)) {
            for (int[] local : regionFile.presentChunks()) {
                if (cancelled.get()) return;
                throttle();
                if (cancelled.get()) return;

                int chunkX = region[0] * 32 + local[0];
                int chunkZ = region[1] * 32 + local[1];
                try {
                    NbtCompound chunk = regionFile.readChunk(local[0], local[1], ChunkExtractor.CHUNK_KEYS);
                    if (chunk == null) continue;

                    ChunkExtractor.ChunkContents contents = ChunkExtractor.extract(
                            chunk, trackedIds, localDimensionId, localPalette, tick);

                    regionBoxes.addAll(contents.structureBoxes());
                    // Recorded even when empty: an empty list is what tells the
                    // apply step that this chunk holds no containers any more.
                    byChunk.put(BlockKey.chunkKey(chunkX, chunkZ), contents.containers());
                    chunksRead.incrementAndGet();
                } catch (IOException torn) {
                    // Expected occasionally: the server may be rewriting this very
                    // file. Skip it - a later scan picks it up - and never abort.
                    chunksFailed.incrementAndGet();
                }
            }
        } catch (IOException e) {
            // A region file we cannot open is not fatal; the rest of the world
            // is still worth indexing.
            ChestTracker.LOG.warn("Skipping region {}: {}", file.getFileName(), e.toString());
            return;
        }

        for (Map.Entry<Long, List<ContainerRecord>> entry : byChunk.entrySet()) {
            List<ContainerRecord> classified = OriginClassifier.classify(entry.getValue(), regionBoxes);
            containersFound.addAndGet(classified.size());
            pending.add(new Batch(dimensionId, entry.getKey(), classified, localPalette));
        }
    }

    /** Backs off while the apply queue is deep, so a fast disk cannot balloon memory. */
    private void throttle() {
        while (pending.size() > 512 && !cancelled.get()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelled.set(true);
                return;
            }
        }
    }

    /**
     * Applies finished batches on the server thread, under a per-tick budget.
     *
     * @param isChunkLoaded  whether a chunk is currently loaded, in which case
     *                       its on-disk copy may be stale and must not be used
     * @param liveScanChunk  indexes a loaded chunk from memory instead. Without
     *                       this the chunks a player is standing in would be the
     *                       one place a world scan never reaches
     * @return how many chunks were applied this tick
     */
    public int drain(java.util.function.BiPredicate<String, Long> isChunkLoaded,
                     java.util.function.BiConsumer<String, Long> liveScanChunk,
                     long tickTimeNanos) {
        if (tickTimeNanos > TICK_TIME_BUDGET_NANOS) return 0;

        int applied = 0;
        Batch batch;
        while (applied < APPLY_BUDGET_PER_TICK && (batch = pending.poll()) != null) {
            applied++;

            if (isChunkLoaded.test(batch.dimensionId(), batch.chunkKey())) {
                // A loaded chunk may hold unsaved changes, so the disk copy is
                // not trustworthy - but it still has to be indexed, from memory.
                liveScanChunk.accept(batch.dimensionId(), batch.chunkKey());
                chunksSkippedLoaded.incrementAndGet();
                continue;
            }

            java.util.Set<Long> positions = new java.util.HashSet<>();
            for (ContainerRecord record : batch.containers()) {
                ContainerRecord remapped = tracker.remap(record, batch.palette(), batch.dimensionId());
                if (remapped == null) continue;
                tracker.record(batch.dimensionId(), remapped);
                positions.add(remapped.pos());
            }
            // For an unloaded chunk the file is the whole truth, so anything the
            // index still holds there and the file does not is gone.
            tracker.reconcileChunk(batch.dimensionId(), batch.chunkKey(), positions);
        }
        return applied;
    }

    public int queuedBatches() {
        return pending.size();
    }
}
