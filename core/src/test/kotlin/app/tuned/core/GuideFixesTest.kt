package app.tuned.core

import app.tuned.core.GuideFixes.Fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideFixesTest {
    @Test
    fun eachSourceCopiesToItsFixedChannels() {
        val targets = GuideFixes.targets(listOf(Fix(1, "tsn1.ca", 60), Fix(2, "tsn1.ca", 0), Fix(3, "espn.us", -30)))
        assertEquals(listOf("fix:1" to 3_600_000L, "fix:2" to 0L), targets["tsn1.ca"])
        assertEquals(listOf("fix:3" to -1_800_000L), targets["espn.us"])
    }

    @Test
    fun fixedIdsAreRecognised() {
        assertTrue(GuideFixes.isFixed(GuideFixes.fixedId(42)))
        assertFalse(GuideFixes.isFixed("cnn.us"))
        assertFalse(GuideFixes.isFixed(null))
    }

    @Test
    fun offsetsReadAsHoursAndMinutes() {
        assertEquals("+1:30", GuideFixes.label(90))
        assertEquals("−2:00", GuideFixes.label(-120))
        assertEquals("0:00", GuideFixes.label(0))
        assertEquals(27, GuideFixes.OFFSETS.size)
        assertTrue(0 in GuideFixes.OFFSETS)
    }
}
