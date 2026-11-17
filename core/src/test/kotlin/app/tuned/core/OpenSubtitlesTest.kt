package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenSubtitlesTest {
    @Test
    fun aFilmIsSearchedByItsCleanTitleAndYear() {
        val search = OpenSubtitles.search("EN - Dune (2021) 4K", null, null, null, null, "")
        assertEquals(OpenSubtitles.Search("Dune", year = 2021, language = "en"), search)
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?languages=en&query=dune&type=movie&year=2021",
            OpenSubtitles.searchUrl(search),
        )
    }

    @Test
    fun aKnownTmdbIdIsUsedInsteadOfTheName() {
        val search = OpenSubtitles.search("Dune (2021)", null, null, null, 438631, "fr")
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?languages=fr&tmdb_id=438631&type=movie",
            OpenSubtitles.searchUrl(search),
        )
    }

    @Test
    fun anEpisodeIsSearchedByItsShowSeasonAndNumber() {
        val search = OpenSubtitles.search("Breaking Bad S02E05", "EN - Breaking Bad", 2, 5, null, "en")
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?episode_number=5&languages=en&query=breaking+bad&season_number=2&type=episode",
            OpenSubtitles.searchUrl(search),
        )
        val byId = search.copy(tmdbId = 1396)
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?episode_number=5&languages=en&parent_tmdb_id=1396&season_number=2&type=episode",
            OpenSubtitles.searchUrl(byId),
        )
    }

    @Test
    fun peopleBeatMachinesThenTheMostDownloadedWins() {
        val search = OpenSubtitles.Search("Dune")
        val results =
            listOf(
                OpenSubtitles.Result(1, "machine", "en", 9_000, machine = true),
                OpenSubtitles.Result(2, "few", "en", 10),
                OpenSubtitles.Result(3, "many", "en", 5_000),
                OpenSubtitles.Result(4, "hi", "en", 8_000, hearingImpaired = true),
                OpenSubtitles.Result(3, "many again", "en", 5_000),
            )
        assertEquals(listOf(3L, 2L, 4L, 1L), OpenSubtitles.rank(results, search).map { it.fileId })
    }

    @Test
    fun otherEpisodesInTheAnswerAreLeftOut() {
        val search = OpenSubtitles.Search("Show", season = 1, episode = 2)
        val results =
            listOf(
                OpenSubtitles.Result(1, "e1", "en", 100, season = 1, episode = 1),
                OpenSubtitles.Result(2, "e2", "en", 50, season = 1, episode = 2),
                OpenSubtitles.Result(3, "unknown", "en", 10),
            )
        assertEquals(listOf(2L, 3L), OpenSubtitles.rank(results, search).map { it.fileId })
    }

    @Test
    fun languagesMapToOpenSubtitlesCodes() {
        assertEquals("en", OpenSubtitles.language(""))
        assertEquals("pt-pt,pt-br", OpenSubtitles.language("pt"))
        assertEquals("zh-cn,zh-tw", OpenSubtitles.language("zh"))
        assertEquals("pt", OpenSubtitles.trackLanguage("pt-pt,pt-br"))
    }

    @Test
    fun keptFilesAreNamedByItemAndLanguage() {
        assertEquals("42-pt.srt", OpenSubtitles.fileName(42, "pt-pt,pt-br"))
        assertEquals(42L, OpenSubtitles.itemOf("42-pt.srt"))
        assertNull(OpenSubtitles.itemOf("notes.txt"))
    }
}
