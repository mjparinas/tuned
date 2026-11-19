package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockCheckTest {
    private val now = 1_795_000_000_000L // 18 November 2026

    @Test
    fun aDeviceBackInTheOldDaysIsWrong() {
        // A box that lost its date often wakes up on 1 January 1970 or its maker's year.
        assertTrue(ClockCheck.looksWrong(0L, null))
        assertTrue(ClockCheck.looksWrong(1_577_836_800_000L, null)) // 1 January 2020
    }

    @Test
    fun aServerFarAwayInTimeMeansTheDeviceIsWrong() {
        assertTrue(ClockCheck.looksWrong(now, serverSkewMs = 3L * 24 * 60 * 60 * 1_000))
        assertTrue(ClockCheck.looksWrong(now, serverSkewMs = -400L * 24 * 60 * 60 * 1_000))
    }

    @Test
    fun timeZonesAndSloppyServersAreFine() {
        assertFalse(ClockCheck.looksWrong(now, null))
        assertFalse(ClockCheck.looksWrong(now, serverSkewMs = 13L * 60 * 60 * 1_000))
        assertFalse(ClockCheck.looksWrong(now, serverSkewMs = -90_000L))
    }

    @Test
    fun readsHttpDates() {
        assertEquals(1_795_041_000_000L, ClockCheck.httpDate("Wed, 18 Nov 2026 22:30:00 GMT"))
        assertNull(ClockCheck.httpDate("yesterday"))
        assertNull(ClockCheck.httpDate(null))
    }
}
