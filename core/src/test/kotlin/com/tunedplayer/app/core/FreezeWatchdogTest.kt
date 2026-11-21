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

    @Test
    fun theWholeAppBeingPausedIsNotAFreeze() {
        val dog = FreezeWatchdog(limitMs = 5_000, startMs = 0, pauseMs = 3_000)
        dog.answered(1_000)
        assertFalse(dog.check(1_000))
        // Android froze the app in the background for a minute: the first tick after waking is not a freeze.
        assertFalse(dog.check(61_000))
        // Nor the next ones, while the screen thread catches up within the limit.
        assertFalse(dog.check(62_000))
        dog.answered(62_100)
        assertFalse(dog.check(63_000))
    }

    @Test
    fun aRealHangIsStillCaughtWhileTicksKeepComing() {
        val dog = FreezeWatchdog(limitMs = 5_000, startMs = 0, pauseMs = 3_000)
        dog.answered(0)
        for (t in 1_000L..4_000L step 1_000) assertFalse(dog.check(t))
        assertTrue(dog.check(5_000))
    }
}
