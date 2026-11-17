package app.tuned.ui.offline

import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.DownloadRow
import org.junit.Test
import kotlin.test.assertEquals

/** The Downloads page lists shows by season instead of one long list (Melvin, 2026-11-17). */
class DownloadGroupsTest {
    private fun row(
        id: Long,
        addedAt: Long,
        show: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = DownloadRow(
        DownloadEntity(id, "item $id", "http://x/$id.mp4", "/x/$id.mp4", addedAt = addedAt),
        logo = null,
        showKey = show?.lowercase(),
        showName = show,
        season = season,
        episode = episode,
    )

    @Test
    fun `episodes group by show and season in episode order, films apart`() {
        val rows =
            listOf(
                row(1, addedAt = 10, show = "Simpsons", season = 5, episode = 2),
                row(2, addedAt = 11, show = "Simpsons", season = 5, episode = 1),
                row(3, addedAt = 12, show = "Simpsons", season = 4, episode = 7),
                row(4, addedAt = 13),
                row(5, addedAt = 14, show = "Futurama", season = 1, episode = 1),
            )
        val groups = groupDownloads(rows, queueOrder = false)
        // Most recently saved first.
        assertEquals(listOf("show:futurama", "movies", "show:simpsons"), groups.map { it.key })
        val simpsons = groups.last() as DownloadGroup.Show
        assertEquals("Simpsons", simpsons.name)
        assertEquals(listOf(4, 5), simpsons.seasons.map { it.number })
        assertEquals(listOf(2L, 1L), simpsons.seasons[1].rows.map { it.download.itemId })
        assertEquals(listOf(4L), (groups[1] as DownloadGroup.Movies).rows.map { it.download.itemId })
    }

    @Test
    fun `while downloading, the group downloading now comes first`() {
        val rows =
            listOf(
                row(1, addedAt = 20),
                row(2, addedAt = 10, show = "Simpsons", season = 5, episode = 2),
                row(3, addedAt = 30, show = "Futurama", season = 1, episode = 1),
            )
        assertEquals(listOf("show:simpsons", "movies", "show:futurama"), groupDownloads(rows, queueOrder = true).map { it.key })
    }
}
