package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelFlipsTest {
    @Test
    fun aSingleFlipChangesTheStreamAtOnce() {
        val flips = ChannelFlips()
        assertTrue(flips.flip(to = 4, now = 1_000))
        assertNull(flips.waiting)
        assertNull(flips.settled())
    }

    @Test
    fun flipsAPauseApartEachChangeTheStream() {
        val flips = ChannelFlips()
        assertTrue(flips.flip(to = 4, now = 1_000))
        assertTrue(flips.flip(to = 5, now = 2_000))
        assertTrue(flips.flip(to = 6, now = 3_000))
    }

    @Test
    fun quickFlipsTuneOnceWhereTheyStop() {
        val flips = ChannelFlips()
        assertTrue(flips.flip(to = flips.from(3) + 1, now = 1_000)) // 4, at once
        assertFalse(flips.flip(to = flips.from(4) + 1, now = 1_200)) // 5, waits
        assertFalse(flips.flip(to = flips.from(4) + 1, now = 1_400)) // counts from 5: 6
        assertFalse(flips.flip(to = flips.from(4) + 1, now = 1_600)) // 7
        assertEquals(7, flips.waiting)
        assertEquals(7, flips.settled())
        assertNull(flips.settled())
    }

    @Test
    fun aFlipWhileOneWaitsKeepsWaitingEvenAfterThePause() {
        val flips = ChannelFlips(burstMs = 600)
        flips.flip(to = 4, now = 0)
        assertFalse(flips.flip(to = 5, now = 100))
        // The timer hasn't fired yet: the stream still changes only once.
        assertFalse(flips.flip(to = 6, now = 900))
        assertEquals(6, flips.settled())
    }

    @Test
    fun resetForgetsAWaitingFlip() {
        val flips = ChannelFlips()
        flips.flip(to = 4, now = 0)
        flips.flip(to = 5, now = 100)
        flips.reset()
        assertNull(flips.settled())
        assertEquals(9, flips.from(9))
    }
}
