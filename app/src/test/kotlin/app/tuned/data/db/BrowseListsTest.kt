package app.tuned.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.core.Search
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The Live TV and Shows tabs read lean queries; they must still show the same things. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseListsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    @Test
    fun eachChannelShowsOnceWithWhatsOnNow() = runTest {
        db.items().insertAll(listOf(item(1, 0, "News", tvgId = "a"), item(2, 0, "Sport", tvgId = "b")))
        // Overlapping guide entries used to list a channel twice without the GROUP BY; the latest start wins.
        db.programmes().insertAll(
            listOf(programme("a", 0, 100, "Morning"), programme("a", 50, 100, "Update"), programme("a", 100, 200, "Later")),
        )

        val rows = db.items().channels(60, emptyList(), adult = false, group = null, query = "", match = "").all()

        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertEquals(listOf("Update", null), rows.map { it.nowTitle })
    }

    @Test
    fun favouritesAndRecentKeepTheirOrder() = runTest {
        db.items().insertAll((1L..3L).map { item(it, 0, "Channel $it") })
        db.library().addFavourite(FavouriteEntity(3, addedAt = 1, position = 1))
        db.library().addFavourite(FavouriteEntity(1, addedAt = 2, position = 2))
        db.library().saveProgress(HistoryEntity(2, 0, 0, watchedAt = 5))
        db.library().saveProgress(HistoryEntity(3, 0, 0, watchedAt = 9))

        assertEquals(
            listOf(3L, 1L),
            db
                .items()
                .favouriteChannels(0, emptyList(), false, null, "", "")
                .all()
                .map { it.id },
        )
        assertEquals(
            listOf(3L, 2L),
            db
                .items()
                .recentChannels(0, emptyList(), false, null, "", "")
                .all()
                .map { it.id },
        )
    }

    @Test
    fun theShowsTabGroupsEpisodesAndHonoursHiddenGroups() = runTest {
        val writer = PlaylistWriter(db)
        writer.begin()
        val rows =
            listOf(
                episode(1, "The Simpsons", "Cartoons"),
                episode(2, "The Simpsons", "Cartoons"),
                episode(3, "Bluey", "Kids"),
            )
        rows.forEach { writer.accept(it.id) }
        writer.add(rows)
        writer.commit()

        val all = db.items().showsTab(emptyList(), adult = false, group = null).all()
        assertEquals(listOf("The Simpsons" to 2, "Bluey" to 1), all.map { it.showName to it.episodes })

        val withoutKids = db.items().showsTab(listOf("g:2:Kids"), adult = false, group = null).all()
        assertEquals(listOf("The Simpsons"), withoutKids.map { it.showName })
    }

    @Test
    fun theUpgradeFillsTheShowsTable() = runTest {
        db.items().insertAll(listOf(episode(1, "Bluey", "Kids")))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TABLE show_groups")

        TunedDatabase.MIGRATION_10_11.migrate(sql)

        assertEquals(
            listOf("Bluey"),
            db
                .items()
                .showsTab(emptyList(), false, null)
                .all()
                .map { it.showName },
        )
    }

    @Test
    fun aSearchFindsAnyWordOfTheTitle() = runTest {
        db.items().insertAll(listOf(episode(1, "The Simpsons", "Cartoons")))
        val found = db.items().searchShows(Search.match("simpsons"), emptyList(), adult = false, limit = 10)
        assertEquals(listOf("The Simpsons"), found.map { it.showName })
    }

    private fun programme(
        channel: String,
        start: Long,
        stop: Long,
        title: String,
    ) = ProgrammeEntity(channelId = channel, start = start, stop = stop, title = title, description = null)

    private fun episode(
        id: Long,
        show: String,
        group: String,
    ) = item(id, 2, "$show S01 E0$id", group = group, show = show)

    private fun item(
        id: Long,
        kind: Int,
        name: String,
        tvgId: String? = null,
        group: String = "All",
        show: String? = null,
    ) = ItemEntity(
        id = id,
        kind = kind,
        name = name,
        searchName = Search.normalize(name),
        groupName = group,
        region = null,
        adult = false,
        logo = null,
        url = "http://example.invalid/$id",
        tvgId = tvgId,
        showKey = show?.let { Search.normalize(it) },
        showName = show,
        season = show?.let { 1 },
        episode = show?.let { id.toInt() },
        sortOrder = id.toInt(),
    )
}
