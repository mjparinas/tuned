package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamLinkTest {
    @Test
    fun `kodi style headers come off the link`() {
        val parts = StreamLink.split("https://cdn.example/a.m3u8|User-Agent=Mozilla%2F5.0&Referer=https%3A%2F%2Fsite.example%2F")
        assertEquals("https://cdn.example/a.m3u8", parts.url)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://site.example/"), parts.headers)
    }

    @Test
    fun `plain links and unknown headers are left alone`() {
        assertEquals(StreamLink.Parts("http://host/u/p/1.ts", emptyMap()), StreamLink.split("http://host/u/p/1.ts"))
        assertEquals(emptyMap(), StreamLink.split("https://cdn.example/a.m3u8|X-Forwarded-For=1.2.3.4").headers)
        assertEquals("https://cdn.example/a.m3u8", StreamLink.address("https://cdn.example/a.m3u8|referrer=https://site.example/"))
    }

    @Test
    fun `join and split round trip`() {
        val headers = mapOf("User-Agent" to "VLC/3.0 (Linux; x86)", "Referer" to "https://site.example/?a=1&b=2")
        val link = StreamLink.join("https://cdn.example/a.m3u8", headers)
        assertEquals(StreamLink.Parts("https://cdn.example/a.m3u8", headers), StreamLink.split(link))
        assertEquals("https://cdn.example/a.m3u8", StreamLink.join("https://cdn.example/a.m3u8", emptyMap()))
    }

    @Test
    fun `vlc options name headers`() {
        assertEquals("User-Agent" to "Mozilla/5.0", StreamLink.vlcOption("#EXTVLCOPT:http-user-agent=Mozilla/5.0"))
        assertEquals("Referer" to "https://site.example/", StreamLink.vlcOption("#EXTVLCOPT:http-referrer=https://site.example/"))
        assertNull(StreamLink.vlcOption("#EXTVLCOPT:network-caching=1000"))
    }

    @Test
    fun `the playlist keeps headers with the link, and ids and sections ignore them`() {
        val list = mutableListOf<PlaylistEntry>()
        M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Example News
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-referrer=https://site.example/
            https://cdn.example/news/index.m3u8
            #EXTINF:-1 group-title="Movies",Example Film
            https://cdn.example/film.mp4|User-Agent=Foo
            #EXTINF:-1 group-title="News",No Headers
            https://cdn.example/plain.m3u8
            """.trimIndent().reader().buffered(),
        ) { list += it }
        val (news, film, plain) = list
        assertEquals(
            StreamLink.Parts(
                "https://cdn.example/news/index.m3u8",
                mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to "https://site.example/",
                ),
            ),
            StreamLink.split(news.url),
        )
        assertEquals(ContentKind.MOVIE, film.kind)
        assertEquals("Foo", StreamLink.split(film.url).headers["User-Agent"])
        assertEquals("https://cdn.example/plain.m3u8", plain.url)
        assertEquals(StableId.of(film.copy(url = "https://cdn.example/film.mp4")), StableId.of(film))
        assertEquals("123", CatchUp.streamId("http://host/live/u/p/123.ts|User-Agent=Foo"))
    }
}
