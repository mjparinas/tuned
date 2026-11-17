package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    private fun parse(text: String): List<PlaylistEntry> {
        val out = mutableListOf<PlaylistEntry>()
        M3uParser.parse(text.reader().buffered()) { out += it }
        return out
    }

    @Test
    fun `reads Xtream m3u_plus entries into the right sections`() {
        val list =
            parse(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="cnn.us" tvg-name="US: CNN HD" tvg-logo="http://x/cnn.png" group-title="US| NEWS",US: CNN HD
                http://host:8080/live/user/pass/101.ts
                #EXTINF:-1 tvg-id="" tvg-name="Dune: Part Two (2024)" group-title="EN - MOVIES 2024",Dune: Part Two (2024)
                http://host:8080/movie/user/pass/202.mkv
                #EXTINF:-1 tvg-name="The Bear S02 E03" group-title="EN - SERIES",The Bear S02 E03
                http://host:8080/series/user/pass/303.mp4
                """.trimIndent(),
            )
        assertEquals(3, list.size)
        val (live, movie, episode) = list
        assertEquals(ContentKind.LIVE, live.kind)
        assertEquals("cnn.us", live.tvgId)
        assertEquals("US", live.region)
        assertEquals("http://x/cnn.png", live.logo)
        assertEquals(ContentKind.MOVIE, movie.kind)
        assertEquals("Dune: Part Two (2024)", movie.name)
        assertNull(movie.tvgId)
        assertEquals("EN", movie.region)
        assertEquals(ContentKind.SERIES, episode.kind)
        assertEquals(EpisodeInfo("The Bear", 2, 3), episode.episode)
        assertEquals(listOf(0, 1, 2), list.map { it.order })
    }

    @Test
    fun `titles and attribute values may hold commas and the title may be missing`() {
        val info = M3uParser.parseExtInf("""#EXTINF:-1 tvg-name="Love, Actually" group-title="Movies, Romance",Love, Actually""")
        assertEquals("Love, Actually", info.title)
        assertEquals("Movies, Romance", info.attributes["group-title"])
        val noTitle = M3uParser.parseExtInf("""#EXTINF:-1 tvg-name="Only Name" group-title="X"""")
        assertEquals("Only Name", noTitle.title)
    }

    @Test
    fun `unquoted attributes, EXTGRP and plain urls without EXTINF still parse`() {
        val list =
            parse(
                """
                #EXTM3U
                #EXTINF:0 tvg-id=abc,Channel A
                #EXTGRP:Sports
                http://a/1.m3u8
                http://a/raw-stream.ts
                """.trimIndent(),
            )
        assertEquals(2, list.size)
        assertEquals("abc", list[0].tvgId)
        assertEquals("Sports", list[0].group)
        assertEquals("raw-stream.ts", list[1].name)
        assertEquals("Uncategorized", list[1].group)
    }

    @Test
    fun `junk lines never stop the parse`() {
        val list = parse("garbage\n#EXTINF:-1 tvg-id=\"unclosed,Bad\nhttp://a/1.ts\n#EXTINF\nhttp://a/2.ts\n")
        assertEquals(listOf("http://a/1.ts", "http://a/2.ts"), list.map { it.url })
    }

    @Test
    fun `an html error page instead of a playlist has no streams`() {
        val page = "<!DOCTYPE html>\n<html><head><title>403 Forbidden</title></head>\n<body>Access denied</body></html>\n"
        assertEquals(0, parse(page).size)
    }

    @Test
    fun `categories decide the section when the url does not`() {
        assertEquals(ContentKind.MOVIE, Classifier.kind("http://a/1.ts", "VOD | Action"))
        assertEquals(ContentKind.SERIES, Classifier.kind("http://a/1.ts", "TV Shows"))
        assertEquals(ContentKind.LIVE, Classifier.kind("http://a/1.ts", "Sports"))
        assertEquals(ContentKind.MOVIE, Classifier.kind("http://a/film.mp4", "Misc"))
        assertTrue(Classifier.isAdult("XXX | Adults", "x"))
    }
}
