package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GuideShiftTest {
    private fun parse(text: String): List<PlaylistEntry> {
        val out = mutableListOf<PlaylistEntry>()
        M3uParser.parse(text.reader().buffered()) { out += it }
        return out
    }

    @Test
    fun `hours are read in the ways playlists write them`() {
        assertEquals(180L, GuideShift.minutes("+3"))
        assertEquals(180L, GuideShift.minutes("3"))
        assertEquals(-90L, GuideShift.minutes("-1.5"))
        assertEquals(-90L, GuideShift.minutes("-1,5"))
        assertEquals(330L, GuideShift.minutes("5:30"))
        assertEquals(-330L, GuideShift.minutes("-5:30"))
        assertEquals(null, GuideShift.minutes(""))
        assertEquals(null, GuideShift.minutes("abc"))
        assertEquals(null, GuideShift.minutes("100"))
    }

    @Test
    fun `a shift is kept in the guide id and read back`() {
        val id = GuideShift.tag("CNN.us", "-3")!!
        assertEquals("CNN.us", GuideShift.base(id))
        assertEquals(-3 * 3_600_000L, GuideShift.offset(id))
        assertEquals("CNN.us", GuideShift.tag("CNN.us", "0"))
        assertEquals("CNN.us", GuideShift.tag("CNN.us", null))
        assertEquals(null, GuideShift.tag(null, "+2"))
        assertEquals("CNN.us", GuideShift.base("CNN.us"))
        assertEquals(0L, GuideShift.offset("CNN.us"))
    }

    @Test
    fun `the playlist header's shift applies unless a channel gives its own`() {
        val (east, west, own) =
            parse(
                """
                #EXTM3U x-tvg-url="https://guide.example/epg.xml" tvg-shift="+1"
                #EXTINF:-1 tvg-id="News.us" tvg-shift="0",News East
                http://cdn.example/1.m3u8
                #EXTINF:-1 tvg-id="News.us",News West
                http://cdn.example/2.m3u8
                #EXTINF:-1 tvg-id="News.us" tvg-shift='-3',News Pacific
                http://cdn.example/3.m3u8
                """.trimIndent(),
            )
        assertEquals("News.us", east.tvgId)
        assertEquals(3_600_000L, GuideShift.offset(west.tvgId!!))
        assertEquals(-3 * 3_600_000L, GuideShift.offset(own.tvgId!!))
        assertEquals("News.us", GuideShift.base(own.tvgId!!))
    }
}
