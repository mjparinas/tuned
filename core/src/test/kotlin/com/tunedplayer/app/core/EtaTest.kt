package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EtaTest {
    @Test
    fun nothingUntilEnoughHasElapsed() {
        assertNull(Eta.remainingMs(done = 100, total = 1_000, startedAt = 1_000, now = 3_999))
    }

    @Test
    fun aSteadyRateGivesTheRestOfTheTime() {
        // 100 of 1_000 in 10s → 10/s → 900 left → 90s.
        assertEquals(90_000, Eta.remainingMs(done = 100, total = 1_000, startedAt = 1_000, now = 11_000))
    }

    @Test
    fun hidesWhenAlmostDoneOrFinished() {
        // 999 of 1_000 in 99.9s → 10/s → 0.1s left, under the hide floor.
        assertNull(Eta.remainingMs(done = 999, total = 1_000, startedAt = 1_000, now = 100_900))
        assertNull(Eta.remainingMs(done = 1_000, total = 1_000, startedAt = 1_000, now = 11_000))
        assertNull(Eta.remainingMs(done = 0, total = 1_000, startedAt = 1_000, now = 11_000))
        assertNull(Eta.remainingMs(done = 50, total = -1, startedAt = 1_000, now = 11_000))
    }

    @Test
    fun noGuessWithoutAStartTime() {
        assertNull(Eta.remainingMs(done = 100, total = 1_000, startedAt = 0, now = 10_000))
    }

    @Test
    fun roundsToStableSecondsMinutesAndHours() {
        assertEquals(Eta.Round.Seconds(5), Eta.round(5_000))
        assertEquals(Eta.Round.Seconds(30), Eta.round(28_000))
        assertEquals(Eta.Round.Minutes(2), Eta.round(90_000))
        assertEquals(Eta.Round.Minutes(12), Eta.round(12 * 60_000L - 1))
        assertEquals(Eta.Round.Hours(1), Eta.round(90 * 60_000L))
        assertEquals(Eta.Round.Hours(1), Eta.round(2 * 3_600_000L - 1))
        assertEquals(Eta.Round.Hours(2), Eta.round(2 * 3_600_000L))
    }
}
