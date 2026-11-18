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
                "US| ESPN 1080 [LIVEEVENT]",
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
    fun hqIsPartOfAName() {
        assertEquals("sky sports news hq", ChannelCopies.key("UK| SKY SPORTS NEWS HQ"))
        assertNotEquals(ChannelCopies.key("UK| SKY SPORTS NEWS"), ChannelCopies.key("UK| SKY SPORTS NEWS HQ"))
    }

    @Test
    fun pictureReadsTheTagsAtTheEnd() {
        assertEquals(ChannelCopies.FHD, ChannelCopies.picture("US| ESPN FHD"))
        assertEquals(ChannelCopies.HD, ChannelCopies.picture("US|  ESPN HD"))
        assertEquals(ChannelCopies.UHD, ChannelCopies.picture("US| ESPN UHD 8K"))
        assertEquals(ChannelCopies.UHD, ChannelCopies.picture("US| ESPN ⁴ᴷ"))
        assertEquals(ChannelCopies.FHD, ChannelCopies.picture("US| ESPN ᶠᴴᴰ"))
        assertEquals(ChannelCopies.HD, ChannelCopies.picture("US| ESPN HD ᴿᴬᵂ"))
        assertEquals(ChannelCopies.FHD, ChannelCopies.picture("US| ESPN 1080 [LIVEEVENT]"))
        assertEquals(ChannelCopies.SD, ChannelCopies.picture("US| ESPN LQ"))
        assertEquals(ChannelCopies.UNKNOWN, ChannelCopies.picture("US| ESPN"))
        assertEquals(ChannelCopies.UNKNOWN, ChannelCopies.picture("UK| SKY SPORTS NEWS HQ"))
        assertEquals(ChannelCopies.UNKNOWN, ChannelCopies.picture("PPV| 8K EXCLUSIVE 05: NO EVENT"))
    }

    @Test
    fun copiesComeFromTheSameCountry() {
        assertTrue(ChannelCopies.sameRegion("US", "US"))
        assertTrue(ChannelCopies.sameRegion(null, "US"))
        assertFalse(ChannelCopies.sameRegion("UK", "IE"))
    }
}
