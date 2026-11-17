package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelCopiesTest {
    @Test
    fun copiesOfOneChannelShareAKey() {
        val espn = ChannelCopies.key("ESPN")
        assertEquals("espn", espn)
        val copies =
            listOf(
                "US| ESPN HD",
                "US| ESPN FHD",
                "|US| ESPN 4K",
                "[US] ESPN (Backup)",
                "ESPN ᴴᴰ",
                "US: ESPN 1080p 60fps",
                "ESPN (B)",
                "ESPN HEVC",
            )
        for (name in copies) {
            assertEquals(espn, ChannelCopies.key(name), name)
        }
    }

    @Test
    fun differentChannelsStayApart() {
        val espn = ChannelCopies.key("US| ESPN HD")
        assertNotEquals(espn, ChannelCopies.key("US| ESPN 2 HD"))
        assertNotEquals(espn, ChannelCopies.key("US| ESPN News"))
        assertNotEquals(ChannelCopies.key("UK| Sky Sports Main Event"), ChannelCopies.key("UK| Sky Sports F1"))
        assertEquals("sky sports f1", ChannelCopies.key("UK| Sky Sports F1 FHD"))
        // Only a country comes off the front: these are two networks' news channels.
        assertNotEquals(ChannelCopies.key("ABC - News"), ChannelCopies.key("CBS - News"))
    }

    @Test
    fun namesWithNothingLeftHaveNoKey() {
        assertNull(ChannelCopies.key("US| HD"))
        assertNull(ChannelCopies.key("4K"))
        assertNull(ChannelCopies.key(""))
    }

    @Test
    fun copiesComeFromTheSameCountry() {
        assertTrue(ChannelCopies.sameRegion("US", "US"))
        assertTrue(ChannelCopies.sameRegion(null, "US"))
        assertFalse(ChannelCopies.sameRegion("UK", "IE"))
    }
}
