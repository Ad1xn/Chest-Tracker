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
import net.minecraft.util.Mth;
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

    /**
     * The registry id of what was searched for, or null.
     *
     * <p>Kept beside the label because the label is for reading and this is
     * for comparing - marking slots in an open container needs to know which
     * item, not what it is called in the player's language.
     */
    private String searchedItemId;

    /** Seconds of turning left, counted down per frame. */
    private float turnSecondsLeft;

    /** When the last frame of turning was, for measuring the one after it. */
    private long turnLastFrameAt;

    /**
     * Beyond this a marker is not worth drawing.
     *
     * <p>Well past render distance on purpose: the containers hardest to find
     * are the ones in chunks that were never loaded, and those are exactly the
     * ones with no terrain drawn to hide them. Cheap, because the number of
     * markers is capped anyway.
     */
    private static final double DRAW_RADIUS = 512.0;

    /**
     * Enough to show a base's worth without filling the screen with wire.
     *
     * <p>Lowered from ninety-six: every marker is a box and a beam, so the
     * count is the one number that decides how much a search costs to draw,
     * and thirty-two containers is already more than anyone reads at once.
     */
    private static final int MAX_BOXES = 32;

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
        turnSecondsLeft = ChestTrackerConfig.get().turnToTarget ? TURN_MAX_SECONDS : 0.0f;
        turnLastFrameAt = System.nanoTime();
    }

    public void clear() {
        timer.clear();
        positions = List.of();
        searchedItemId = null;
    }

    /** The registry id being looked for, for marking slots. Null when inactive. */
    public String searchedItemId() {
        return searchedItemId;
    }

    /** Names the item a selection is about, so open containers can mark it. */
    public void searchingFor(String itemId) {
        this.searchedItemId = itemId;
    }

    /**
     * How much bigger a box gets per block of distance.
     *
     * <p>A one-block cube is a couple of pixels across at a hundred blocks -
     * findable only if you already know where to look, which defeats the point.
     * Growing it with distance keeps roughly the apparent size instead of
     * shrinking to nothing, so scanning a base for the box actually works.
     */
    private static final double GROW_PER_BLOCK = 0.002;

    /** Where "far" begins, and the box starts growing in earnest. */
    private static final double FAR_FROM = 200.0;

    /** Growth per block past {@link #FAR_FROM}. */
    private static final double FAR_GROW_PER_BLOCK = 0.004;

    /**
     * Growth is snapped to this, and line width to a whole pixel.
     *
     * <p>Because growth depends on distance, a box that grows smoothly also
     * <em>shrinks</em> smoothly as the player walks towards it - and a box
     * quietly changing size every frame does not read as a box changing size.
     * It reads as a box that will not sit still on the chest, which is exactly
     * how it was reported. Snapping means it changes in occasional steps
     * instead, and holds still in between.
     */
    private static final double GROW_STEP = 0.25;

    /**
     * Distance at which a box starts growing at all.
     *
     * <p>Nothing nearer gets any bigger than the block it sits on. Growing
     * from zero distance made close boxes fat enough to lose the chest inside
     * them, which is the opposite of pointing at it - and up close the box is
     * perfectly legible at its true size.
     */
    private static final double GROW_FROM = 24.0;

    /**
     * Past this the box stops growing.
     *
     * <p>Cut hard, twice. The box is the precise half of a marker: it says
     * which block, and a box that fills the screen from four hundred blocks
     * away says nothing at all - it is not a bigger answer, it is a lost one.
     * Distance is the trail's job, and the trail is what was supposed to grow.
     * Three quarters of a block of swell is enough to keep the outline from
     * collapsing into a dot without it ever stopping being a box.
     */
    private static final double MAX_GROW = 0.75;

    /** Below this the container is in plain sight and a beam only clutters it. */
    private static final double BEAM_MIN_DISTANCE = 8.0;

    /**
     * How far the trail of marks rises above its container up close.
     *
     * <p>This is the part that has to be seen from across a world, so it grows
     * with distance rather than staying a fixed height: thirty-two blocks is a
     * tall column at ten blocks and an invisible speck at a thousand.
     */
    private static final double BEAM_HEIGHT = 48.0;

    /** Added to the trail's height per block of distance. */
    private static final double BEAM_HEIGHT_PER_BLOCK = 0.6;

    /** Tall enough to cross the sky; more would simply leave the world. */
    private static final double MAX_BEAM_HEIGHT = 420.0;

    /**
     * How much of what is left to turn is taken per second.
     *
     * <p>Per second, and applied per frame, because a turn driven from the game
     * tick moves the view twenty times a second however fast the game is
     * drawing. Vanilla's own interpolation hides some of that but not the
     * change in speed, which is what still read as steppy at a hundred frames a
     * second. Framerate now changes how smooth it looks, not how fast it turns.
     */
    private static final float TURN_EASE_PER_SECOND = 6.0f;

    /** Close enough to stop turning; below this the correction is invisible. */
    private static final float TURN_DONE_DEGREES = 0.75f;

    /**
     * The fastest the view may swing, in degrees a second.
     *
     * <p>Easing alone still snapped: a quarter of a hundred-and-eighty degree
     * gap is a huge first step, and no amount of smoothing after that hides it.
     * Capping the speed makes a long turn take longer rather than start
     * violently.
     */
    private static final float TURN_MAX_PER_SECOND = 150.0f;

    /** A frame longer than this is a stutter; treat it as one frame's worth. */
    private static final float MAX_FRAME_SECONDS = 0.1f;

    /** A turn is abandoned after this, so it can never fight the mouse for long. */
    private static final float TURN_MAX_SECONDS = 1.5f;

    private static final float BASE_LINE_WIDTH = 2.0f;
    private static final float LINE_WIDTH_PER_BLOCK = 0.02f;
    private static final float MAX_LINE_WIDTH = 5.0f;

    /** Whether there is anything for the world renderer to draw. */
    public boolean hasBoxes() {
        return timer.isActive() && !positions.isEmpty()
                && ChestTrackerConfig.get().highlightDisplay().drawsBoxes();
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
        // Read once. This runs per container per frame, and the config lookup
        // does not change between two boxes of the same frame.
        ChestTrackerConfig config = ChestTrackerConfig.get();
        boolean beams = config.guideBeam;
        float[] nearestColour = config.nearestRgb();
        float[] otherColour = config.otherRgb();

        int drawn = 0;
        for (Long position : positions) {
            if (drawn >= MAX_BOXES) break;

            double x = BlockKey.x(position);
            double y = BlockKey.y(position);
            double z = BlockKey.z(position);

            double dx = x + 0.5 - eye.x;
            double dy = y + 0.5 - eye.y;
            double dz = z + 0.5 - eye.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            double distance = Math.sqrt(distSq);

            // Past the far plane nothing is drawn at all - the projection
            // clips it, which is why a container thousands of blocks away
            // showed nothing whatever its size. Those are pulled in to the
            // edge of what can be drawn and marked there, pointing the right
            // way, like a waypoint on the horizon. The size still comes from
            // the real distance, so a clamped marker reads as a far one.
            double limit = horizon();
            double pull = distance > limit ? limit / distance : 1.0;

            // Both the box and its lines grow with distance, so a container
            // across a base is something you can find by looking rather than
            // something you have to already be pointing at.
            double grow = growthAt(distance);
            float width = (float) Math.round(Math.min(MAX_LINE_WIDTH,
                    BASE_LINE_WIDTH + Math.max(0.0, distance - GROW_FROM) * LINE_WIDTH_PER_BLOCK));

            // The nearest one is picked out, because that is the one the action
            // bar is talking about and the one the player is walking towards.
            boolean nearest = position == pos;
            float[] colour = nearest ? nearestColour : otherColour;

            double drawX = dx * pull - 0.5;
            double drawY = dy * pull - 0.5;
            double drawZ = dz * pull - 0.5;

            HighlightBox.emit(pose, lines, drawX, drawY, drawZ,
                    colour[0], colour[1], colour[2], 0.9f, grow, width);

            // The column is what carries at range, and the only part of this
            // that means anything where no terrain is drawn to place it.
            if (beams && distance > BEAM_MIN_DISTANCE) {
                double beamHeight = Math.min(MAX_BEAM_HEIGHT,
                        BEAM_HEIGHT + distance * BEAM_HEIGHT_PER_BLOCK);
                HighlightBox.beam(pose, lines,
                        drawX + 0.5, drawY + 1.5, drawZ + 0.5, beamHeight * pull,
                        colour[0], colour[1], colour[2], 0.75f, width);
            }
            drawn++;
        }
    }

    /**
     * How far out geometry can still be drawn.
     *
     * <p>The projection's far plane follows the render distance, so anything
     * beyond it is clipped no matter how large it is drawn. Held just inside
     * that, because a marker sitting exactly on the plane flickers in and out
     * as the camera moves.
     */
    private static double horizon() {
        Minecraft client = Minecraft.getInstance();
        int chunks = client.options == null ? 8 : client.options.getEffectiveRenderDistance();
        return Math.max(48.0, chunks * 16.0 * 0.85);
    }

    /**
     * How much larger than its block a marker is drawn at a given distance.
     *
     * <p>Two rates. Up to two hundred blocks the box only has to stay legible,
     * so it barely grows; past that it is competing with the horizon and grows
     * five times as fast. The result is snapped to half a block so that walking
     * changes it in steps rather than continuously - see {@link #GROW_STEP}.
     */
    private static double growthAt(double distance) {
        double grow = Math.max(0.0, distance - GROW_FROM) * GROW_PER_BLOCK;
        if (distance > FAR_FROM) grow += (distance - FAR_FROM) * FAR_GROW_PER_BLOCK;
        grow = Math.min(MAX_GROW, grow);
        return Math.round(grow / GROW_STEP) * GROW_STEP;
    }

    /**
     * Eases the view round to face the nearest match.
     *
     * <p>Over a few ticks rather than in one frame: snapping the camera is
     * disorienting, and a player who was already turning finds themselves
     * fighting it. Short enough to be over before it feels like a fight.
     */
    public void turnTowardsTarget() {
        if (turnSecondsLeft <= 0.0f) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || positions.isEmpty() || !timer.isActive()) {
            turnSecondsLeft = 0.0f;
            return;
        }

        long now = System.nanoTime();
        float seconds = Math.min(MAX_FRAME_SECONDS, (now - turnLastFrameAt) / 1_000_000_000.0f);
        turnLastFrameAt = now;
        if (seconds <= 0.0f) return;
        turnSecondsLeft -= seconds;

        double dx = BlockKey.x(pos) + 0.5 - player.getX();
        double dy = BlockKey.y(pos) + 0.5 - player.getEyeY();
        double dz = BlockKey.z(pos) + 0.5 - player.getZ();

        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        // Shortest way round, so facing 179 and wanting -179 turns two degrees
        // rather than three hundred and fifty eight.
        float yawGap = Mth.wrapDegrees(wantYaw - player.getYRot());
        float pitchGap = wantPitch - player.getXRot();

        // Arrived. Stopping here rather than nudging on for another second is
        // what keeps it from feeling like the mouse is being held.
        if (Math.abs(yawGap) < TURN_DONE_DEGREES && Math.abs(pitchGap) < TURN_DONE_DEGREES) {
            turnSecondsLeft = 0.0f;
            return;
        }

        // The ease is expressed per second and converted for this frame's
        // length, so the curve is the same at thirty frames a second as at two
        // hundred - only the number of steps along it changes.
        float ease = Math.min(1.0f, TURN_EASE_PER_SECOND * seconds);
        float limit = TURN_MAX_PER_SECOND * seconds;

        player.setYRot(player.getYRot() + Mth.clamp(yawGap * ease, -limit, limit));
        player.setXRot(player.getXRot() + Mth.clamp(pitchGap * ease, -limit, limit));
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

        if (!ChestTrackerConfig.get().highlightDisplay().writesActionBar()) return;

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
