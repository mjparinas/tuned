package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbTest {
    @Test
    fun providerTagsAreStrippedFromTheTitle() {
        assertEquals(Tmdb.Query("The Matrix", 1999), Tmdb.query("EN - The Matrix (1999) 4K"))
        assertEquals(Tmdb.Query("Dune Part Two", 2024), Tmdb.query("|EN| Dune Part Two [FHD] 2024"))
        assertEquals(Tmdb.Query("Breaking Bad", null), Tmdb.query("Breaking Bad"))
        assertEquals(Tmdb.Query("Amélie", 2001), Tmdb.query("FR: Amélie (2001) MULTI"))
    }

    @Test
    fun aV3KeyGoesInTheLinkAndAV4TokenDoesNot() {
        val q = Tmdb.Query("The Matrix", 1999)
        assertEquals(
            "https://api.themoviedb.org/3/search/movie?query=The+Matrix&primary_release_year=1999&include_adult=false&api_key=abc",
            Tmdb.search(q, tv = false, key = "abc"),
        )
        assertFalse("api_key" in Tmdb.search(q, tv = true, key = "eyJhbGciOi.x.y"))
        assertTrue(Tmdb.search(q, tv = true, key = "abc").contains("/search/tv?"))
        assertEquals("https://api.themoviedb.org/3/movie/603/videos?api_key=abc", Tmdb.videos(603, tv = false, key = "abc"))
    }

    @Test
    fun detailsComeInOneCallAndRuntimeReadsNaturally() {
        assertEquals(
            "https://api.themoviedb.org/3/movie/603?append_to_response=videos,credits&api_key=abc",
            Tmdb.details(603, tv = false, key = "abc"),
        )
        assertEquals("2h 16m", Tmdb.runtime(136))
        assertEquals("48m", Tmdb.runtime(48))
        assertEquals("2h", Tmdb.runtime(120))
        assertNull(Tmdb.runtime(0))
    }

    @Test
    fun anOfficialYouTubeTrailerIsPreferred() {
        val videos =
            listOf(
                Tmdb.Video("YouTube", "Teaser", "teaser"),
                Tmdb.Video("Vimeo", "Trailer", "vimeo", official = true),
                Tmdb.Video("YouTube", "Trailer", "fan"),
                Tmdb.Video("YouTube", "Trailer", "official", official = true),
            )
        assertEquals("official", Tmdb.trailer(videos))
        assertEquals("teaser", Tmdb.trailer(videos.take(2)))
        assertNull(Tmdb.trailer(emptyList()))
    }
}
