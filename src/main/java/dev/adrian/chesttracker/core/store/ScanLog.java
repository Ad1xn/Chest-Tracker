package dev.adrian.chesttracker.core.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Which region files have already been read, and what they looked like then.
 *
 * <p>Without this the offline scan is redone in full every time a world loads,
 * which on a large world is minutes of work to rediscover what was already
 * known - and a scan interrupted by quitting starts again from nothing, so a
 * world that takes longer to scan than a session lasts is never finished at
 * all. That is the case this exists for.
 *
 * <p>A region is identified by its path and remembered with its size and
 * modification time. Anything that changes a chunk rewrites its region file, so
 * a region whose size and timestamp both still match cannot contain a container
 * the index has not already seen. When either differs the region is read again.
 *
 * <p>Deliberately not the index's own format. This is a few thousand short
 * lines, it is rewritten constantly during a scan, and a plain text file that
 * can be deleted or read by hand is worth more here than a compact one - if it
 * is ever wrong, the fix is to delete it and let a scan rebuild it.
 */
public final class ScanLog {

    /** What a region looked like when it was last read. */
    public record Mark(long size, long modifiedAt) {}

    private final Map<String, Mark> marks = new HashMap<>();

    /** True if this file has been read and has not changed since. */
    public boolean isCurrent(String key, long size, long modifiedAt) {
        Mark mark = marks.get(key);
        return mark != null && mark.size() == size && mark.modifiedAt() == modifiedAt;
    }

    public void mark(String key, long size, long modifiedAt) {
        marks.put(key, new Mark(size, modifiedAt));
    }

    public void forget(String key) {
        marks.remove(key);
    }

    public void clear() {
        marks.clear();
    }

    public int size() {
        return marks.size();
    }

    /**
     * Reads a log, or returns an empty one.
     *
     * <p>Never throws for bad content. A log that cannot be read means at worst
     * that a scan repeats work, which is exactly what happens without one - so
     * a corrupt file is not worth failing a world load over.
     */
    public static ScanLog read(Path file) {
        ScanLog log = new ScanLog();
        if (!Files.isRegularFile(file)) return log;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // key is last, because a path may contain anything including
                // the separator, while the two numbers cannot.
                int firstTab = line.indexOf('\t');
                int secondTab = firstTab < 0 ? -1 : line.indexOf('\t', firstTab + 1);
                if (secondTab < 0) continue;
                try {
                    long size = Long.parseLong(line.substring(0, firstTab));
                    long modifiedAt = Long.parseLong(line.substring(firstTab + 1, secondTab));
                    log.mark(line.substring(secondTab + 1), size, modifiedAt);
                } catch (NumberFormatException ignored) {
                    // One unreadable line costs one rescanned region.
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return new ScanLog();
        }
        return log;
    }

    /**
     * Writes the log beside the index, replacing any previous one.
     *
     * <p>Through a temporary file, like the index: a log truncated by a crash
     * mid-write would claim regions were scanned that were not, and a half
     * written log is worse than none.
     */
    public void write(Path file) throws IOException {
        StringBuilder out = new StringBuilder(marks.size() * 48);
        for (Map.Entry<String, Mark> entry : marks.entrySet()) {
            out.append(entry.getValue().size()).append('\t')
                    .append(entry.getValue().modifiedAt()).append('\t')
                    .append(entry.getKey()).append('\n');
        }

        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
