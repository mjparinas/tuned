package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistLinkTest {
    @Test
    fun `an xtream get link becomes a login`() {
        assertEquals(
            PlaylistLink.Xtream("http://tv.example.com:8080", "me", "p&w"),
            PlaylistLink.parse("http://tv.example.com:8080/get.php?username=me&password=p%26w&type=m3u_plus&output=ts"),
        )
    }

    @Test
    fun `player api and a path prefix are understood`() {
        assertEquals(
            PlaylistLink.Xtream("https://example.com/iptv", "a", "b"),
            PlaylistLink.parse(" https://example.com/iptv/player_api.php?password=b&username=a "),
        )
    }

    @Test
    fun `a get link without a password stays a plain link`() {
        assertEquals(PlaylistLink.M3u("http://h.com/get.php?username=me"), PlaylistLink.parse("http://h.com/get.php?username=me"))
    }

    @Test
    fun `other links are plain m3u, with http added when missing`() {
        val link = "https://iptv-org.github.io/iptv/index.m3u"
        assertEquals(PlaylistLink.M3u(link), PlaylistLink.parse(link))
        assertEquals(PlaylistLink.M3u("http://example.com/list.m3u8"), PlaylistLink.parse("example.com/list.m3u8"))
    }

    @Test
    fun `text that is not a link is refused`() {
        assertNull(PlaylistLink.parse(""))
        assertNull(PlaylistLink.parse("my playlist"))
        assertNull(PlaylistLink.parse("ftp://example.com/a.m3u"))
        assertNull(PlaylistLink.parse("notalink"))
    }

    @Test
    fun `reads the guide named in the playlist header`() {
        val several = """#EXTM3U x-tvg-url="https://epg.example.com/a.xml.gz,https://b.xml""""
        assertEquals("https://epg.example.com/a.xml.gz", PlaylistLink.headerGuide(several))
        val single = "#EXTM3U url-tvg='http://g.example.com/guide.xml' refresh=\"3600\""
        assertEquals("http://g.example.com/guide.xml", PlaylistLink.headerGuide(single))
        assertNull(PlaylistLink.headerGuide("#EXTM3U"))
        assertNull(PlaylistLink.headerGuide("#EXTINF:-1 tvg-url=\"http://x.com/g.xml\",Channel"))
    }
}
