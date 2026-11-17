package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleOffsetTest {
    @Test
    fun stepsAddUpAndStopAtTheLimit() {
        assertEquals(1_100L, SubtitleOffset.step(1_000, SubtitleOffset.SMALL_STEP_MS))
        assertEquals(-1_000L, SubtitleOffset.step(0, -SubtitleOffset.BIG_STEP_MS))
        assertEquals(SubtitleOffset.LIMIT_MS, SubtitleOffset.step(29_500, SubtitleOffset.BIG_STEP_MS))
        assertEquals(-SubtitleOffset.LIMIT_MS, SubtitleOffset.step(-29_950, -SubtitleOffset.SMALL_STEP_MS))
    }

    @Test
    fun secondsHaveOneDecimalPlaceAndNoSign() {
        assertEquals("0.0", SubtitleOffset.seconds(0))
        assertEquals("1.5", SubtitleOffset.seconds(1_500))
        assertEquals("0.3", SubtitleOffset.seconds(-300))
        assertEquals("30.0", SubtitleOffset.seconds(30_000))
    }

    @Test
    fun cuesMoveByTheOffsetAndSampleTimedCuesTakeItAsTheirStart() {
        val unset = Long.MIN_VALUE + 1
        assertEquals(6_500_000L, SubtitleOffset.shift(5_000_000, 1_500, unset))
        assertEquals(4_000_000L, SubtitleOffset.shift(5_000_000, -1_000, unset))
        assertEquals(-200_000L, SubtitleOffset.shift(unset, -200, unset))
    }
}
