package com.custom.keyboard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class LiveSourcesTest {
    @Test
    fun stepsCountFromTheFirstReadingThenAcrossDaysAndRestarts() {
        var s = StepMath.update(StepMath.State("", -1, -1), "2026-10-01", 5000)
        assertEquals(0, s.last - s.baseline)
        s = StepMath.update(s, "2026-10-01", 5600)
        assertEquals(600, s.last - s.baseline)
        // Phone restarted the same day: the counter starts again at 0.
        s = StepMath.update(s, "2026-10-01", 100)
        assertEquals(700, s.last - s.baseline)
        // Next day: steps since the last reading count for the new day.
        s = StepMath.update(s, "2026-10-02", 150)
        assertEquals(50, s.last - s.baseline)
    }

    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }

    @Test
    fun countdownCountsWholeDays() {
        assertEquals(10, Countdown.daysBetween(day(2026, 10, 9), day(2026, 10, 19)))
        assertEquals(-1, Countdown.daysBetween(day(2026, 10, 9), day(2026, 10, 8)))
        assertEquals(365, Countdown.daysBetween(day(2026, 1, 1), day(2027, 1, 1)))
    }

    @Test
    fun yearlyCountdownRollsOverToNextYear() {
        val next = Countdown.nextYearly(day(2026, 10, 9), 2, 3)
        assertEquals(2027, next.get(Calendar.YEAR))
        assertEquals(0, Countdown.daysBetween(day(2026, 10, 9), Countdown.nextYearly(day(2026, 10, 9), 9, 9)))
        // 29 February falls back to the 28th in other years.
        assertEquals(28, Countdown.nextYearly(day(2026, 10, 9), 1, 29).get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun countdownDateRoundTrips() {
        assertEquals("2026-03-07", Countdown.format(Countdown.parse("2026-03-07")!!))
        assertEquals(null, Countdown.parse("soon"))
    }
}
