package com.tunedplayer.app.core

import com.tunedplayer.app.core.FrameRates.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameRatesTest {
    private val m24 = Mode(1, 24f)
    private val m23 = Mode(2, 23.976f)
    private val m50 = Mode(3, 50f)
    private val m59 = Mode(4, 59.94f)
    private val m60 = Mode(5, 60f)
    private val tv = listOf(m23, m24, m50, m59, m60)

    @Test
    fun picksTheMatchingRate() {
        assertEquals(m50, FrameRates.pick(50f, tv, m60))
        assertEquals(m50, FrameRates.pick(25f, tv, m60))
        assertEquals(m24, FrameRates.pick(24f, tv, m60))
        assertEquals(m23, FrameRates.pick(23.976f, tv, m60))
        assertEquals(m59, FrameRates.pick(29.97f, tv, m60))
        assertEquals(m59, FrameRates.pick(59.94f, tv, m60))
    }

    @Test
    fun staysWhenAlreadyRightOrNothingFits() {
        assertNull(FrameRates.pick(30f, tv, m60))
        assertNull(FrameRates.pick(50f, tv, m50))
        assertNull(FrameRates.pick(25f, listOf(m60), m60))
        assertNull(FrameRates.pick(-1f, tv, m60))
        assertNull(FrameRates.pick(1000f, tv, m60))
    }
}
