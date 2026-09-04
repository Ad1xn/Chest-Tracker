package dev.adrian.chesttracker.core;

import dev.adrian.chesttracker.core.highlight.HighlightTimer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighlightTimerTest {

    private static HighlightTimer timer() {
        return new HighlightTimer(10_000L, 2_000L, 1_000L);
    }

    @Test
    void staysActiveWhileThePlayerKeepsApproaching() {
        HighlightTimer timer = timer();
        timer.start(100, 0);

        long now = 0;
        double distance = 100;
        // Walk towards it for a minute - far longer than the base duration.
        for (int step = 0; step < 60; step++) {
            now += 1_000;
            distance -= 1.5;
            assertTrue(timer.update(distance, now),
                    "approaching at t=" + now + " should keep the highlight alive");
        }
    }

    @Test
    void expiresQuicklyOnceThePlayerWalksAway() {
        HighlightTimer timer = timer();
        timer.start(20, 0);

        assertTrue(timer.update(24, 1_000), "still inside the grace period");
        assertTrue(timer.update(28, 2_000));
        // Grace is 2s from the first receding sample, so it is gone by then.
        assertFalse(timer.update(32, 3_500), "walking away must let the highlight expire");
    }

    @Test
    void pathingAroundAnObstacleDoesNotCancelIt() {
        // The case that breaks a naive velocity dot product: the player rounds a
        // wall, briefly moving away, then resumes closing in.
        HighlightTimer timer = timer();
        timer.start(30, 0);

        assertTrue(timer.update(28, 1_000));
        assertTrue(timer.update(31, 2_000), "a detour is not giving up");
        assertTrue(timer.update(33, 3_000));
        assertTrue(timer.update(27, 4_000), "back on track");
        // Having made real progress, the full duration is available again.
        assertTrue(timer.update(20, 12_000));
    }

    @Test
    void standingStillRunsDownTheBaseTimerRatherThanTheGrace() {
        // Someone who stopped to fight a mob has not walked away, so they should
        // get the full duration - not the short receding grace.
        HighlightTimer timer = timer();
        timer.start(50, 0);

        assertTrue(timer.update(50, 3_000), "stationary well inside the base duration");
        assertTrue(timer.update(50, 9_000));
        assertFalse(timer.update(50, 10_500), "but the base timer still expires eventually");
    }

    @Test
    void smallJitterCountsAsNeitherProgressNorRetreat() {
        HighlightTimer timer = timer();
        timer.start(40, 0);

        // Head bob and strafing, all under the epsilon.
        assertTrue(timer.update(40.3, 1_000));
        assertTrue(timer.update(39.8, 2_000));
        assertTrue(timer.update(40.2, 3_000));
        assertFalse(timer.update(40.1, 11_000), "jitter must not refresh the timer indefinitely");
    }

    @Test
    void reportsRemainingTimeAndCanBeCleared() {
        HighlightTimer timer = timer();
        timer.start(10, 0);

        assertTrue(timer.isActive());
        assertEquals(10_000L, timer.remainingMs(0));
        assertEquals(6_000L, timer.remainingMs(4_000));

        timer.clear();
        assertFalse(timer.isActive());
        assertEquals(0L, timer.remainingMs(0));
        assertFalse(timer.update(1, 1_000));
    }

    @Test
    void neverGoesNegative() {
        HighlightTimer timer = timer();
        timer.start(10, 0);
        assertEquals(0L, timer.remainingMs(999_999L));
    }
}
