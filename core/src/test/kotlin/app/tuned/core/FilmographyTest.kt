package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilmographyTest {
    private fun film(
        id: Long,
        title: String,
        date: String?,
    ) = Filmography.Credit(id, tv = false, title = title, date = date, character = null, poster = null, episodes = null)

    @Test
    fun oldestFirstWithUndatedLast() {
        val ordered =
            Filmography.order(
                listOf(
                    film(1, "Heat", "1995-12-15"),
                    film(2, "Upcoming", null),
                    film(3, "Mean Streets", "1973-10-02"),
                    film(4, "Casino", "1995-11-22"),
                ),
            )
        assertEquals(listOf("Mean Streets", "Casino", "Heat", "Upcoming"), ordered.map { it.title })
    }

    @Test
    fun aShowWithSeveralPartsIsListedOnce() {
        val parts =
            listOf(
                Filmography.Credit(7, true, "The Show", "2010-01-01", "Ann", "/a.jpg", 10),
                Filmography.Credit(7, true, "The Show", "2008-05-01", "Ann (voice)", null, 2),
            )
        val one = Filmography.order(parts).single()
        assertEquals("2008-05-01", one.date)
        assertEquals("Ann / Ann (voice)", one.character)
        assertEquals(12, one.episodes)
        assertEquals("/a.jpg", one.poster)
    }

    @Test
    fun talkShowsAndNewsAreLeftOut() {
        val credits =
            listOf(
                film(1, "Heat", "1995-12-15"),
                Filmography.Credit(9, true, "Late Night", "2014-02-17", "Self", null, 3, genreIds = listOf(10767)),
                Filmography.Credit(10, true, "Evening News", "2001-01-01", "Self", null, 1, genreIds = listOf(10763)),
            )
        assertEquals(listOf("Heat"), Filmography.order(credits).map { it.title })
    }

    @Test
    fun playlistNamesMatchWithTagsAndYears() {
        val heat = film(1, "Heat", "1995-12-15")
        assertTrue(Filmography.matches("EN - Heat (1995) 4K", heat))
        assertTrue(Filmography.matches("|EN| Heat", heat))
        assertFalse(Filmography.matches("Heat (2013)", heat))
        assertFalse(Filmography.matches("Heat Wave", heat))
        assertTrue(Filmography.matches("Spider-Man: No Way Home", film(2, "Spider-Man: No Way Home", "2021-12-15")))
    }
}
