package com.tunedplayer.app.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerTest {
    @Test
    fun `stored links round-trip`() {
        val url = MediaServer.link(MediaServer.Product.Jellyfin, "abc 1")
        assertTrue(MediaServer.isLink(url))
        assertEquals(MediaServer.Link(MediaServer.Product.Jellyfin, "abc 1"), MediaServer.parse(url))
        assertNull(MediaServer.parse("http://example.com/stream"))
    }

    @Test
    fun `show stand-ins round-trip`() {
        val item = MediaServer.Item("42", "The Bear", null, "Drama", year = 2022)
        val entry = MediaServer.showStandIn(MediaServer.Product.Plex, item, 3)
        assertEquals(ContentKind.SERIES, entry.kind)
        assertEquals(MediaServer.StandIn(MediaServer.Product.Plex, "42"), MediaServer.standIn(entry.url))
    }

    @Test
    fun `live channels keep a guide id`() {
        val channel = MediaServer.Channel("7", "News", null, "News", guideId = "news.hd", number = 7)
        val entry = MediaServer.live(MediaServer.Product.Emby, channel, 0)
        assertEquals(ContentKind.LIVE, entry.kind)
        assertEquals("news.hd", entry.tvgId)
        assertTrue(entry.url.startsWith(MediaServer.Product.Emby.scheme))
    }

    @Test
    fun `markers identify each product`() {
        assertEquals(MediaServer.Product.Plex, MediaServer.Product.ofMarker("plex:"))
        assertEquals(MediaServer.Product.Jellyfin, MediaServer.Product.ofMarker("jellyfin:"))
        assertEquals(MediaServer.Product.Emby, MediaServer.Product.ofMarker("emby:"))
        assertNull(MediaServer.Product.ofMarker("stalker:"))
    }
}
