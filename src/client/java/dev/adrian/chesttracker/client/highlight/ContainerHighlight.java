package dev.adrian.chesttracker.client.highlight;

import dev.adrian.chesttracker.client.platform.ClientCompat;
import dev.adrian.chesttracker.config.ChestTrackerConfig;
import dev.adrian.chesttracker.core.highlight.HighlightTargets;
import dev.adrian.chesttracker.core.highlight.HighlightTimer;
import dev.adrian.chesttracker.core.util.BlockKey;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The container the player is currently being guided to.
 *
 * <p>Selecting a result closes the screen and starts a highlight. It persists
 * while the player is making progress towards it and fades shortly after they
 * give up - see {@link HighlightTimer}, where that rule lives and is tested.
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

    /**
     * Every container being pointed at, nearest first is not assumed - the
     * nearest is recomputed as the player moves.
     */
    private List<Long> positions = List.of();

    private long pos;
    private String dimensionId;
    private String label;

    /** Beyond this there is nothing on screen to draw a box around anyway. */
    private static final double DRAW_RADIUS = 160.0;

    /** Enough to show a base's worth without filling the screen with wire. */
    private static final int MAX_BOXES = 96;

    private ContainerHighlight() {}

    public static ContainerHighlight get() {
        return INSTANCE;
    }

    /** Points at one container - a row picked out of the list. */
    public void select(long pos, String dimensionId, String label) {
        select(List.of(pos), dimensionId, label);
    }

    /**
     * Points at every container holding the chosen item.
     *
     * <p>The action bar still describes only the nearest, because a bearing to
     * nine places at once is not guidance. The boxes are what say "and also
     * there, and there".
     */
    public void select(List<Long> positions, String dimensionId, String label) {
        this.positions = positions == null ? List.of() : List.copyOf(positions);
        long nearest = this.positions.isEmpty() ? 0 : this.positions.get(0);
        selectPrimary(nearest, dimensionId, label);
    }

    private void selectPrimary(long pos, String dimensionId, String label) {
        ChestTrackerConfig config = ChestTrackerConfig.get();
        this.timer = new HighlightTimer(config.highlightDurationMs(),
                config.highlightRecedingGraceMs(), SAMPLE_INTERVAL_MS);
        this.pos = pos;
        this.dimensionId = dimensionId;
        this.label = label;

        LocalPlayer player = Minecraft.getInstance().player;
        double distance = player == null ? 0 : distanceTo(player);
        timer.start(distance, System.currentTimeMillis());
    }

    public void clear() {
        timer.clear();
        positions = List.of();
    }

    /** Whether there is anything for the world renderer to draw. */
    public boolean hasBoxes() {
        return timer.isActive() && !positions.isEmpty()
                && ChestTrackerConfig.get().inWorldHighlight;
    }

    /**
     * Draws a box around each highlighted container.
     *
     * <p>Called from the render thread, once per frame, so it reads state and
     * allocates nothing. Positions far enough away to be off screen are skipped
     * rather than drawn and clipped - a base with hundreds of matching barrels
     * would otherwise submit hundreds of boxes to be thrown away.
     *
     * @param eye the camera position; boxes are drawn relative to it
     */
    public void drawBoxes(PoseStack.Pose pose, VertexConsumer lines, Vec3 eye) {
        int drawn = 0;
        for (Long position : positions) {
            if (drawn >= MAX_BOXES) break;

            double x = BlockKey.x(position);
            double y = BlockKey.y(position);
            double z = BlockKey.z(position);

            double dx = x + 0.5 - eye.x;
            double dy = y + 0.5 - eye.y;
            double dz = z + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > DRAW_RADIUS * DRAW_RADIUS) continue;

            // The nearest one is picked out, because that is the one the action
            // bar is talking about and the one the player is walking towards.
            boolean nearest = position == pos;
            HighlightBox.emit(pose, lines, x - eye.x, y - eye.y, z - eye.z,
                    nearest ? 1.0f : 0.25f,
                    nearest ? 0.82f : 0.85f,
                    nearest ? 0.2f : 1.0f,
                    0.9f);
            drawn++;
        }
    }

    /**
     * Re-points the guidance at whichever highlighted container is closest now.
     *
     * <p>The choosing itself lives in {@code core} so it can be tested without
     * a game - which is where it caught that height has to count, a case this
     * loop got right but nothing was checking.
     */
    private void followNearest(LocalPlayer player) {
        if (positions.size() < 2) return;
        pos = HighlightTargets.nearest(positions,
                player.getBlockX(), player.getBlockY(), player.getBlockZ());
    }

    public boolean isActive() {
        return timer.isActive();
    }

    public long pos() {
        return pos;
    }

    /**
     * Advances the highlight and shows guidance.
     *
     * <p>Guidance goes to the action bar rather than a custom HUD overlay: it is
     * plain vanilla API, behaves identically on every supported version, and in
     * a base of any size a bearing and a distance are more use than an outline
     * the player cannot see through a wall anyway.
     */
    public void tick() {
        if (!timer.isActive()) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            timer.clear();
            return;
        }
        if (!player.level().dimension().identifier().toString().equals(dimensionId)) {
            // Guidance across dimensions would be nonsense.
            timer.clear();
            return;
        }

        // Walking past one of them makes another the nearest; the arrow should
        // follow rather than keep pointing behind.
        followNearest(player);

        double distance = distanceTo(player);
        if (!timer.update(distance, System.currentTimeMillis())) {
            return;
        }

        if (distance <= ARRIVAL_DISTANCE) {
            ClientCompat.actionBar(Component.literal(label + " - you are here")
                    .withStyle(ChatFormatting.GREEN));
            return;
        }

        ClientCompat.actionBar(Component.literal(String.format("%s  %s  %.0fm",
                label, bearing(player), distance)).withStyle(ChatFormatting.AQUA));
    }

    private double distanceTo(LocalPlayer player) {
        return Math.sqrt(BlockKey.distanceSq(
                BlockKey.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ()), pos));
    }

    /** Where the container is relative to where the player is facing. */
    private String bearing(LocalPlayer player) {
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
