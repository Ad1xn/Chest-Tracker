package dev.adrian.chesttracker.core.anvil;

import dev.adrian.chesttracker.core.model.ContainerRecord;
import dev.adrian.chesttracker.core.model.Origin;
import dev.adrian.chesttracker.core.util.BlockKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides whether a container was generated or placed, from what a scan can see.
 *
 * <p>Signals, strongest first:
 * <ol>
 *   <li><b>Observed placement</b> - handled elsewhere, since it needs a live
 *       player event. It outranks everything here, and {@link Origin#merge}
 *       guarantees this classifier can never overwrite it.
 *   <li><b>Inside a structure bounding box</b> - durable, and still true after
 *       the chest has been looted.
 *   <li><b>An unrolled loot table</b> - zero false positives, but only
 *       identifies generated chests nobody has opened yet.
 * </ol>
 *
 * <p>Anything else stays {@link Origin#UNKNOWN}, which is a real answer rather
 * than a failure: on a vanilla server a client genuinely cannot tell.
 *
 * <p><b>Boxes must be gathered across a whole region, not one chunk.</b> A
 * chest's own chunk usually stores only a structure <i>reference</i>, with the
 * bounding box recorded in whichever chunk the structure started in. Classifying
 * chunk-by-chunk would therefore miss most structure chests.
 */
public final class OriginClassifier {

    private OriginClassifier() {}

    /** Classifies one chunk's containers against boxes from that chunk alone. */
    public static List<ContainerRecord> classify(ChunkExtractor.ChunkContents contents) {
        return classify(contents.containers(), contents.structureBoxes());
    }

    /**
     * Classifies containers against every structure box known so far.
     *
     * @param containers containers to classify
     * @param boxes      structure bounding boxes, ideally region-wide
     */
    public static List<ContainerRecord> classify(Collection<ContainerRecord> containers,
                                                 Collection<ChunkExtractor.StructureBox> boxes) {
        List<ContainerRecord> classified = new ArrayList<>(containers.size());
        for (ContainerRecord container : containers) {
            classified.add(classifyOne(container, boxes));
        }
        return classified;
    }

    private static ContainerRecord classifyOne(ContainerRecord container,
                                               Collection<ChunkExtractor.StructureBox> boxes) {
        // An unrolled loot table already settled it during extraction.
        if (container.origin() == Origin.NATURAL) return container;

        int x = BlockKey.x(container.pos());
        int y = BlockKey.y(container.pos());
        int z = BlockKey.z(container.pos());

        for (ChunkExtractor.StructureBox box : boxes) {
            if (box.contains(x, y, z)) {
                return container.withOrigin(Origin.NATURAL, null);
            }
        }
        return container;
    }
}
