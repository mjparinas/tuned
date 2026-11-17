package app.tuned.ui.offline

import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.DownloadRow
import org.junit.Test
import kotlin.test.assertEquals

/** The Downloads page lists each show once, then its seasons, then its episodes (Melvin, 2026-11-17). */
class DownloadLibraryTest {
    private fun row(
        id: Long,
        addedAt: Long,
        show: String? = null,
        season: Int? = null,
        episode: Int? = null,
        done: Boolean = true,
    ) = DownloadRow(
        DownloadEntity(
            id,
            "item $id",
            "http://x/$id.mp4",
            "/x/$id.mp4",
            state = if (done) DownloadEntity.DONE else DownloadEntity.QUEUED,
            addedAt = addedAt,
        ),
        logo = "still-$id",
        showKey = show?.lowercase(),
        showName = show,
        showLogo = show?.let { "poster-$it" },
        season = season,
        episode = episode,
    )

    @Test
    fun `each show is listed once with its poster, its seasons and episodes in order`() {
        val library =
            DownloadLibrary.of(
                listOf(
                    row(1, addedAt = 10, show = "Simpsons", season = 5, episode = 2),
                    row(2, addedAt = 11, show = "Simpsons", season = 5, episode = 1),
                    row(3, addedAt = 12, show = "Simpsons", season = 4, episode = 7),
                    row(4, addedAt = 13),
                ),
            )
        val simpsons = library.shows.single()
        assertEquals("poster-Simpsons", simpsons.poster)
        assertEquals(listOf(4, 5), simpsons.seasons.map { it.number })
        assertEquals(listOf(2L, 1L), simpsons.seasons[1].rows.map { it.download.itemId })
        assertEquals("still-2", simpsons.seasons[1].picture)
        assertEquals(listOf(4L), library.movies.map { it.download.itemId })
    }

    @Test
    fun `shows still downloading come first, then the most recently saved`() {
        val library =
            DownloadLibrary.of(
                listOf(
                    row(1, addedAt = 10, show = "Old"),
                    row(2, addedAt = 30, show = "New"),
                    row(3, addedAt = 20, show = "Coming", done = false),
                ),
            )
        assertEquals(listOf("coming", "new", "old"), library.shows.map { it.showKey })
    }

    @Test
    fun `films on their way come first, then saved films newest first`() {
        val library = DownloadLibrary.of(listOf(row(1, addedAt = 10), row(2, addedAt = 20), row(3, addedAt = 30, done = false)))
        assertEquals(listOf(3L, 2L, 1L), library.movies.map { it.download.itemId })
    }
}
