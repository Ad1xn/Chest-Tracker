package dev.adrian.chesttracker.client.highlight;

import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.highlight.HighlightTargets;
import dev.adrian.chesttracker.core.highlight.HighlightTimer;
import dev.adrian.chesttracker.core.util.BlockKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The containers the player is currently being guided to.
 *
 * <p>A selection is a <em>set</em> of positions, not one. Picking a row in the
 * search screen names a single container, but the in-container hotkey means
 * "where is this stuff", and the answer is every container holding it.
 *
 * <p>Guidance still names one at a time - an action bar has room for one
 * bearing - so it points at whichever of them is nearest, re-chosen as the
 * player moves. The rest are kept rather than discarded because they are what
 * an in-world highlight will draw: see {@link #targets()}, which exists for
 * exactly that and is why this class holds a list at all today.
 *
 * <p>The highlight persists while the player is making progress and fades
 * shortly after they give up - see {@link HighlightTimer}, where that rule
 * lives and is tested.
 */
public final class ContainerHighlight {

    private static final ContainerHighlight INSTANCE = new ContainerHighlight();

    /** Close enough that the player can see the container for themselves. */
    private static final double ARRIVAL_DISTANCE = 3.0;

    /** How often progress towards the target is judged; matches the default. */
    private static final long SAMPLE_INTERVAL_MS = 1000;

    /**
     * Rebuilt on each selection from the current settings.
     *
     * <p>Built per selection rather than once: the timer holds its durations,
     * so a single instance made at startup would ignore the settings screen
     * until the game restarted - which is how these two settings came to have
     * no effect at all.
     */
    private HighlightTimer timer = HighlightTimer.defaults();

    /** Packed positions, all in {@link #dimensionId}. Empty when inactive. */
    private List<Long> targets = List.of();

    private String dimensionId;
    private String label;

    private ContainerHighlight() {}

    public static ContainerHighlight get() {
        return INSTANCE;
    }

    /** Guides to one container - the search screen's "I want that one". */
    public void select(long pos, String dimensionId, String label) {
        select(List.of(pos), dimensionId, label);
    }

    /**
     * Guides to the nearest of several, and remembers them all.
     *
     * <p>An empty list clears rather than leaving a highlight with nothing to
     * point at, which would otherwise sit on screen bearing zero degrees.
     */
    public void select(List<Long> positions, String dimensionId, String label) {
        if (positions == null || positions.isEmpty()) {
            clear();
            return;
        }
        ChestTrackerConfig config = ChestTrackerConfig.get();
        this.timer = new HighlightTimer(config.highlightDurationMs(),
                config.highlightRecedingGraceMs(), SAMPLE_INTERVAL_MS);
        this.targets = List.copyOf(positions);
        this.dimensionId = dimensionId;
        this.label = label;

        LocalPlayer player = Minecraft.getInstance().player;
        double distance = player == null ? 0 : distanceTo(player, nearestTo(player));
        timer.start(distance, System.currentTimeMillis());
    }

    public void clear() {
        timer.clear();
        targets = List.of();
    }

    public boolean isActive() {
        return timer.isActive();
    }

    /**
     * Every container this selection matched.
     *
     * <p>Guidance uses only the nearest; this is the whole set, for drawing all
     * of them in the world once that exists.
     */
    public List<Long> targets() {
        return targets;
    }

    /** The dimension every target is in, or null when inactive. */
    public String dimensionId() {
        return dimensionId;
    }

    /** What the highlight is looking for, or null when inactive. */
    public String label() {
        return label;
    }

    /** The one being guided to right now: the nearest. Zero when inactive. */
    public long pos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return targets.isEmpty() || player == null ? 0L : nearestTo(player);
    }

    /**
     * Advances the highlight and shows guidance.
     *
     * <p>Guidance goes to the action bar rather than a custom HUD overlay: it is
     * plain vanilla API, behaves identically on every supported version, and in
     * a base of any size a bearing and a distance are more use than an outline
     * the player cannot see through a wall anyway. Container screens report
     * themselves as in-game UI, so this is readable without closing the chest
     * the player pressed the key in.
     */
    public void tick() {
        if (!timer.isActive()) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || targets.isEmpty()) {
            clear();
            return;
        }
        if (!player.level().dimension().identifier().toString().equals(dimensionId)) {
            // Guidance across dimensions would be nonsense.
            clear();
            return;
        }

        long target = nearestTo(player);
        double distance = distanceTo(player, target);
        if (!timer.update(distance, System.currentTimeMillis())) {
            return;
        }

        if (distance <= ARRIVAL_DISTANCE) {
            ClientCompat.actionBar(Component.literal(label + " - you are here" + remainder())
                    .withStyle(ChatFormatting.GREEN));
            return;
        }

        ClientCompat.actionBar(Component.literal(String.format("%s  %s  %.0fm%s",
                label, bearing(player, target), distance, remainder()))
                .withStyle(ChatFormatting.AQUA));
    }

    /**
     * How many other containers hold it.
     *
     * <p>Without this the hotkey looks like it found one container when it
     * found thirty, and the player has no reason to keep walking past the first.
     */
    private String remainder() {
        return targets.size() > 1 ? "  (+" + (targets.size() - 1) + " more)" : "";
    }

    /**
     * The closest target to the player.
     *
     * <p>Re-chosen every tick rather than fixed at selection time, so walking
     * towards a different one of them hands guidance over instead of marching
     * the player past it.
     */
    private long nearestTo(LocalPlayer player) {
        return HighlightTargets.nearest(targets,
                player.getBlockX(), player.getBlockY(), player.getBlockZ());
    }

    private double distanceTo(LocalPlayer player, long pos) {
        return Math.sqrt(BlockKey.distanceSq(
                BlockKey.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ()), pos));
    }

    /** Where the container is relative to where the player is facing. */
    private String bearing(LocalPlayer player, long pos) {
        double dx = BlockKey.x(pos) + 0.5 - player.getX();
        double dz = BlockKey.z(pos) + 0.5 - player.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Math.floorMod((long) (targetYaw - player.getYRot() + 360 + 22.5), 360L) / 45;

        String horizontal = switch ((int) relative) {
            case 0 -> "ahead";
            case 1 -> "ahead-right";
            case 2 -> "right";
            case 3 -> "behind-right";
            case 4 -> "behind";
            case 5 -> "behind-left";
            case 6 -> "left";
            default -> "ahead-left";
        };

        int dy = BlockKey.y(pos) - player.getBlockY();
        if (dy > 3) return horizontal + " and up";
        if (dy < -3) return horizontal + " and down";
        return horizontal;
    }
}
