package dev.adrian.chesttracker.core.highlight;

/**
 * Decides how long a container stays highlighted after the player picks it.
 *
 * <p>The rule: keep it while the player is heading for it, and drop it shortly
 * after they give up.
 *
 * <p>The obvious implementation - a dot product of velocity against the
 * direction to the target - fails badly indoors. Walking around a wall, up a
 * staircase, or around furniture all read as "moving away" for a second or two,
 * and the highlight vanishes exactly when it is most needed. So progress is
 * judged by <em>distance over time</em> instead of instantaneous heading.
 *
 * <p>Three states, sampled about once a second:
 * <ul>
 *   <li><b>Approaching</b> - closer than the best distance so far. Resets the
 *       timer to full, so a player who keeps making progress keeps the
 *       highlight indefinitely.
 *   <li><b>Stationary</b> - neither meaningfully closer nor further. The base
 *       timer simply runs down; someone who stopped to fight a mob is not
 *       treated as having given up.
 *   <li><b>Receding</b> - meaningfully further away. The remaining time is
 *       clamped to a short grace period.
 * </ul>
 *
 * <p>Pure logic with no game types, so the behaviour is unit-testable rather
 * than something to eyeball in-game.
 */
public final class HighlightTimer {

    /** Distance change below this is noise - head bob, strafing, a single step. */
    private static final double MOVEMENT_EPSILON = 0.75;

    private final long baseDurationMs;
    private final long recedingGraceMs;
    private final long sampleIntervalMs;

    private boolean active;
    private double bestDistance;
    private double lastSampledDistance;
    private long lastSampleAt;
    private long expiresAt;

    public HighlightTimer(long baseDurationMs, long recedingGraceMs, long sampleIntervalMs) {
        this.baseDurationMs = baseDurationMs;
        this.recedingGraceMs = recedingGraceMs;
        this.sampleIntervalMs = sampleIntervalMs;
    }

    public static HighlightTimer defaults() {
        return new HighlightTimer(45_000L, 10_000L, 1_000L);
    }

    /** Starts (or restarts) the highlight. */
    public void start(double distance, long nowMs) {
        active = true;
        bestDistance = distance;
        lastSampledDistance = distance;
        lastSampleAt = nowMs;
        expiresAt = nowMs + baseDurationMs;
    }

    public void clear() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    /** Milliseconds left before the highlight disappears; 0 when inactive. */
    public long remainingMs(long nowMs) {
        return active ? Math.max(0L, expiresAt - nowMs) : 0L;
    }

    /**
     * Advances the timer.
     *
     * @return true while the highlight should still be drawn
     */
    public boolean update(double distance, long nowMs) {
        if (!active) return false;

        if (nowMs - lastSampleAt >= sampleIntervalMs) {
            double delta = lastSampledDistance - distance; // positive means closer
            if (distance < bestDistance - MOVEMENT_EPSILON) {
                // Genuine progress: a new personal best on the way there.
                bestDistance = distance;
                expiresAt = nowMs + baseDurationMs;
            } else if (delta < -MOVEMENT_EPSILON) {
                // Receding. Do not extend an already-shorter deadline.
                expiresAt = Math.min(expiresAt, nowMs + recedingGraceMs);
            }
            // Stationary: leave the deadline alone and let it run down.
            lastSampledDistance = distance;
            lastSampleAt = nowMs;
        }

        if (nowMs >= expiresAt) {
            active = false;
            return false;
        }
        return true;
    }
}
