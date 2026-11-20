package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreezeWatchdogTest {
    @Test
    fun aScreenThreadThatKeepsAnsweringIsNeverReported() {
        val dog = FreezeWatchdog(limitMs = 5_000, startMs = 0)
        for (t in 1_000L..60_000L step 1_000) {
            dog.answered(t - 500)
            assertFalse(dog.check(t))
        }
    }

    @Test
    fun aLongHangIsReportedOnce() {
        val dog = FreezeWatchdog(limitMs = 5_000, startMs = 0)
        assertFalse(dog.check(4_999))
        assertTrue(dog.check(5_000))
        assertFalse(dog.check(6_000))
        assertFalse(dog.check(60_000))
        assertEquals(60_000, dog.stuckFor(60_000))
    }

    @Test
    fun aSecondHangAfterRecoveringIsReportedAgain() {
        val dog = FreezeWatchdog(limitMs = 5_000, startMs = 0)
        assertTrue(dog.check(5_000))
        dog.answered(7_000)
        assertFalse(dog.check(11_999))
        assertTrue(dog.check(12_000))
    }
}
