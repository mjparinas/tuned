package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamApiTest {
    private val links = XtreamApi.Links("host.example:8080", "user", "pass")

    @Test
    fun `api streams become the entries m3u_plus would list, with the same ids`() {
        val live =
            XtreamApi.live(
                links,
                XtreamApi.Stream("101", "US: CNN HD", "http://logo.example/cnn.png", "US| NEWS", epgId = "cnn.us"),
                0,
            )
        assertEquals("http://host.example:8080/live/user/pass/101.ts", live.url)
        assertEquals(ContentKind.LIVE, live.kind)
        assertEquals("US", live.region)
        assertEquals("cnn.us", live.tvgId)
        val fromM3u = live.copy(url = "http://host.example:8080/user/pass/101.ts")
        assertEquals(StableId.of(fromM3u), StableId.of(live))

        val movie = XtreamApi.movie(links, XtreamApi.Stream("202", "Dune (2024)", null, "EN - MOVIES", extension = "mkv"), 1)
        assertEquals("http://host.example:8080/movie/user/pass/202.mkv", movie.url)
        assertEquals(ContentKind.MOVIE, movie.kind)
        assertEquals("EN", movie.region)
    }

    @Test
    fun `the server's adult flag counts`() {
        val night = XtreamApi.live(links, XtreamApi.Stream("9", "Night Channel", null, "Premium", adult = true), 0)
        assertTrue(night.adult)
    }

    @Test
    fun `a show is one stand-in row until its episodes arrive under the same show`() {
        val standIn = XtreamApi.seriesStandIn(XtreamApi.Stream("77", "The Bear", "http://img.example/bear.jpg", "EN - SERIES"), 2)
        assertEquals(ContentKind.SERIES, standIn.kind)
        assertEquals("77", XtreamApi.standInId(standIn.url))
        assertNull(XtreamApi.standInId("http://host.example/series/u/p/1.mkv"))
        val episode = XtreamApi.episode(links, "The Bear", "EN - SERIES", null, "3003", "The Bear S02 E03", 2, 3, "mp4", 0)
        assertEquals("http://host.example:8080/series/user/pass/3003.mp4", episode.url)
        assertEquals(EpisodeInfo("The Bear", 2, 3), episode.episode)
        assertEquals(Episodes.showKey(standIn.episode!!.showName), Episodes.showKey(episode.episode!!.showName))
        assertEquals("The Bear S01E04", XtreamApi.episode(links, "The Bear", "g", null, "1", "", 1, 4, null, 0).name)
    }
}
