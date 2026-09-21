package courseclock.timetable

import courseclock.timetable.testing.Timeline
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test fun realModeUsesWallClock() {
        assertEquals(999L, Timeline(123, 0, 0.0, false).now(999999, 999))
    }
    @Test fun pauseDoesNotMoveEvenIfWallClockChanges() {
        assertEquals(123L, Timeline(123, 50, 0.0, true).now(99_000, 1_000_000))
    }
    @Test fun speedUsesElapsedClockNotWallClock() {
        val clock = Timeline(1_000_000, 500, 60.0, true)
        assertEquals(1_120_000L, clock.now(2500, -999))
        assertEquals(2000L, clock.delayUntil(1_120_000, 500, 0))
    }
    @Test fun fractionalSpeedNeverFiresEarly() {
        assertEquals(4L, Timeline(100, 0, 0.25, true).delayUntil(101, 0, 0))
        assertEquals(1L, Timeline(100, 0, 120.0, true).delayUntil(101, 0, 0))
    }
    @Test fun pausedFutureHasNoRealDeadline() {
        assertNull(Timeline(100, 0, 0.0, true).delayUntil(200, 0, 0))
    }
    @Test fun overdueHasZeroDelay() {
        assertEquals(0L, Timeline(100, 0, 10.0, true).delayUntil(99, 100, 0))
    }
    @Test fun reanchorPreservesInstantAcrossSpeedChange() {
        val first = Timeline(1_000_000, 100, 5.0, true)
        val instant = first.now(2100, 0)
        val next = Timeline(instant, 2100, 60.0, true)
        assertEquals(instant, next.now(2100, 0))
        assertEquals(instant + 60_000, next.now(3100, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeSpeed() { Timeline(0, 0, -1.0, true) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteSpeed() { Timeline(0, 0, Double.NaN, true) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnboundedSpeed() { Timeline(0, 0, 121.0, true) }
}
