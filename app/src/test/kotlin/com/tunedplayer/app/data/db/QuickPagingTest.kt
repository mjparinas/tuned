package com.tunedplayer.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live TV, Movies and Shows show their first rows before they are counted (Melvin, 2026-11-28: "Instead of loading
 * all channels, can we progressively load them?"), and still show exactly what Room's own paged lists showed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickPagingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counts = ListCounts(db, scope)

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun refresh(size: Int = PAGE.toInt()) = PagingSource.LoadParams.Refresh<Int>(null, size, false)

    private suspend fun <T : Any> PagingSource<Int, T>.page(params: PagingSource.LoadParams<Int> = refresh()) =
        load(params) as PagingSource.LoadResult.Page<Int, T>

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> = page(refresh(10_000)).data

    private fun live(
        source: LiveSource = LiveSource.All,
        hidden: List<String> = emptyList(),
        search: String = "",
    ) = QuickLists.channels(source, 0, hidden, false, null, search, Search.match(search), "", listOf("any"), "", 0)

    @Test
    fun theFirstPageNeverWaitsOnTheCount() = runTest {
        db.items().insertAll((1L..300L).map { item(it, 0, "Channel $it") })

        val first = QuickPagingSource(db, counts, live()).page()

        assertEquals((1L..PAGE).toList(), first.data.map { it.id })
        assertEquals(COUNT_UNDEFINED, first.itemsAfter, "the list is only as long as what has loaded until it is counted")
        assertEquals(PAGE.toInt(), first.nextKey)
        val next = QuickPagingSource(db, counts, live()).page(PagingSource.LoadParams.Append(PAGE.toInt(), PAGE.toInt(), false))
        assertEquals((PAGE + 1..PAGE * 2).toList(), next.data.map { it.id })
    }

    @Test
    fun theCountLandsInTheBackgroundAndTheListStartsOverAtFullLength() = runBlocking {
        db.items().insertAll((1L..300L).map { item(it, 0, "Channel $it") })
        val first = QuickPagingSource(db, counts, live())
        first.page()

        withTimeout(10_000) { while (!first.invalid) delay(10) }
        assertEquals(300, counts.exact(live()))

        val again = QuickPagingSource(db, counts, live()).page(PagingSource.LoadParams.Refresh(60, PAGE.toInt(), false))
        assertEquals(60, again.itemsBefore)
        assertEquals(300 - 60 - PAGE.toInt(), again.itemsAfter)
        assertEquals(61L, again.data.first().id)
    }

    @Test
    fun aShortListIsCountedByItsFirstPage() = runTest {
        db.items().insertAll((1L..5L).map { item(it, 0, "Channel $it") })

        val page = QuickPagingSource(db, counts, live()).page()

        assertEquals(5, page.data.size)
        assertEquals(0, page.itemsAfter)
        assertNull(page.nextKey)
        assertEquals(5, counts.exact(live()))
    }

    @Test
    fun aChangeKeepsTheLastCountAsAGuess() = runTest {
        db.items().insertAll((1L..300L).map { item(it, 0, "Channel $it") })
        counts.record(live(), 300, counts.stamp(live()))

        counts.changed(setOf("items"))

        assertNull(counts.exact(live()))
        assertEquals(300, counts.guess(live()))
        // While it is recounted, the list keeps its length rather than shrinking to one page.
        assertEquals(300 - PAGE.toInt(), QuickPagingSource(db, counts, live()).page().itemsAfter)
    }

    @Test
    fun aChangeToAnotherTableKeepsTheCount() = runTest {
        counts.record(movies(TitleSort.Order.PROVIDER), 42, counts.stamp(movies(TitleSort.Order.PROVIDER)))

        counts.changed(setOf("programmes"))

        assertEquals(42, counts.exact(movies(TitleSort.Order.PROVIDER)))
    }

    @Test
    fun everySortOfAListSharesItsCount() = runTest {
        counts.record(movies(TitleSort.Order.PROVIDER), 42, counts.stamp(movies(TitleSort.Order.PROVIDER)))

        assertEquals(42, counts.exact(movies(TitleSort.Order.RATING)))
    }

    @Test
    fun theGuidesClockLeavesTheLastCountAsAGuess() = runTest {
        val now = QuickLists.channels(LiveSource.All, 1_000, emptyList(), false, null, "", "", "", listOf("any"), "", 0)
        val later = QuickLists.channels(LiveSource.All, 301_000, emptyList(), false, null, "", "", "", listOf("any"), "", 0)
        counts.record(now, 300, counts.stamp(now))

        assertNull(counts.exact(later))
        assertEquals(300, counts.guess(later))
    }

    @Test
    fun liveTvShowsWhatRoomsListsShowed() = runTest {
        db.items().insertAll(
            listOf(
                item(1, 0, "News", tvgId = "a"),
                item(2, 0, "Sport"),
                item(3, 0, "Movies 24/7"),
                item(4, 0, "Kids", group = "Kids"),
            ),
        )
        db.programmes().insertAll(listOf(ProgrammeEntity(channelId = "a", start = 0, stop = 100, title = "Morning", description = null)))
        db.library().addFavourite(FavouriteEntity(4, addedAt = 1, position = 1))
        db.library().addFavourite(FavouriteEntity(1, addedAt = 2, position = 2))
        db.library().saveProgress(HistoryEntity(2, 0, 0, watchedAt = 5))
        val hidden = listOf("247", "g:0:Kids")
        val items = db.items()

        fun quick(source: LiveSource) = QuickLists.channels(source, 50, hidden, false, null, "", "", "", listOf("any"), "", 0)
        val all = QuickPagingSource(db, counts, quick(LiveSource.All)).all()
        assertEquals(items.channels(50, hidden, false, null, "", "", "", listOf("any"), "", 0).all(), all)
        assertEquals("Morning", all.first().nowTitle)
        assertEquals(
            items.favouriteChannels(50, emptyList(), false, null, "", "", "", listOf("any"), "", 0).all(),
            QuickPagingSource(
                db,
                counts,
                QuickLists.channels(LiveSource.Favourites, 50, emptyList(), false, null, "", "", "", listOf("any"), "", 0),
            ).all(),
        )
        assertEquals(
            items.recentChannels(50, hidden, false, null, "", "", "", listOf("any"), "", 0).all(),
            QuickPagingSource(db, counts, quick(LiveSource.Recent)).all(),
        )
        assertEquals(listOf(1L, 2L), all.map { it.id })
    }

    @Test
    fun searchFindsTheSameChannels() = runTest {
        db.items().insertAll(listOf(item(1, 0, "CBC News"), item(2, 0, "TSN Sport"), item(3, 0, "CNN News")))

        val found = QuickPagingSource(db, counts, live(search = "news")).all()

        assertEquals(listOf(1L, 3L), found.map { it.id })
    }

    @Test
    fun moviesAndShowsShowWhatRoomsListsShowed() = runTest {
        db.items().insertAll(
            listOf(item(10, 1, "Zulu"), item(11, 1, "Alpha"), item(12, 1, "Mike")) +
                (20L..23L).map { item(it, 2, "Show ${it % 2} E$it", show = "Show ${it % 2}") },
        )
        for (order in TitleSort.Order.entries) {
            assertEquals(
                db.sortedItems(order, 1, emptyList(), false, null, false, "", "", 0).all(),
                QuickPagingSource(db, counts, movies(order)).all(),
                "movies by $order",
            )
            assertEquals(
                db.sortedShows(order, emptyList(), false, null, false, "", "").all(),
                QuickPagingSource(db, counts, QuickLists.shows(order, emptyList(), false, null, false, "", "", emptyList())).all(),
                "shows by $order",
            )
        }
        ShowGroups.rebuild(db)
        for (order in TitleSort.Order.entries) {
            assertEquals(
                db.sortedShowsTab(order, emptyList(), false, null).all(),
                QuickPagingSource(db, counts, QuickLists.showsTab(order, emptyList(), false, null)).all(),
                "the Shows tab by $order",
            )
            assertEquals(
                db.sortedShowsTab(order, emptyList(), false, null).all(),
                QuickPagingSource(db, counts, QuickLists.showsTab(order, emptyList(), false, null, oneRowEach = true)).all(),
                "the flat Shows tab by $order",
            )
        }
    }

    @Test
    fun theQuickCountsCountWhatTheListsShow() = runTest {
        db.items().insertAll(
            listOf(
                item(1, 0, "News", tvgId = "a"),
                item(2, 0, "Sport 24/7"),
                item(3, 0, "Movies", tvgId = "b"),
                item(4, 0, "Kids", group = "Kids"),
                item(5, 0, "Docs"),
            ),
        )
        db.programmes().insertAll(
            listOf(
                ProgrammeEntity(channelId = "a", start = 0, stop = 100, title = "Morning", description = null, genre = 1),
                ProgrammeEntity(channelId = "b", start = 0, stop = 100, title = "Film", description = null, genre = 2),
            ),
        )
        db.library().addFavourite(FavouriteEntity(1, addedAt = 1, position = 1))
        db.library().addFavourite(FavouriteEntity(2, addedAt = 2, position = 2))
        db.library().saveProgress(HistoryEntity(3, 0, 0, watchedAt = 5))
        val hidden = listOf("247", "g:0:Kids")
        for (source in listOf(LiveSource.All, LiveSource.Favourites, LiveSource.Recent, LiveSource.OwnList(9))) {
            for (genre in listOf(0, 1)) {
                val list = QuickLists.channels(source, 50, hidden, false, null, "", "", "", listOf("any"), "", genre)
                assertEquals(
                    QuickPagingSource(db, counts, list).all().size,
                    db.quick().count(list.count()),
                    "$source with genre $genre",
                )
            }
        }
    }

    @Test
    fun theShowsTabSkipsGroupingWhenEveryShowHasOneRow() = runTest {
        val sql = db.openHelper.writableDatabase
        // Ties, missing ratings and years, and titles left empty, in every sort.
        val rows =
            listOf(
                "('b', 'All', NULL, 0, 'B', NULL, 3, 4, 10, 2001, 7.5, 'b')",
                "('a', 'All', NULL, 0, 'A', NULL, 1, 2, 10, NULL, NULL, '')",
                "('c', 'All', NULL, 0, 'C', NULL, 2, 1, 30, 2001, 7.5, 'c')",
                "('d', 'All', NULL, 0, 'D', NULL, 5, 3, 20, 1999, NULL, '')",
                "('e', 'Kids', NULL, 0, 'E', NULL, 5, 5, 0, 2010, 9.0, 'e')",
            )
        sql.execSQL("INSERT INTO show_groups (${ShowGroups.COLUMNS}) VALUES ${rows.joinToString()}")
        assertTrue(db.shows().oneRowEach().first())
        for (order in TitleSort.Order.entries) {
            for (hidden in listOf(emptyList(), listOf("g:2:Kids"))) {
                val flat = QuickLists.showsTab(order, hidden, false, null, oneRowEach = true)
                val all = QuickPagingSource(db, counts, flat).all()
                assertEquals(db.sortedShowsTab(order, hidden, false, null).all(), all, "the flat Shows tab by $order")
                assertEquals(all.size, db.quick().count(flat.count()))
                val grouped = QuickLists.showsTab(order, hidden, false, null)
                assertEquals(all.size, db.quick().count(grouped.count()))
            }
        }

        sql.execSQL("INSERT INTO show_groups (${ShowGroups.COLUMNS}) VALUES ('a', 'Kids', NULL, 0, 'A', NULL, 1, 9, 0, NULL, NULL, '')")

        assertEquals(false, db.shows().oneRowEach().first())
        assertEquals(5, db.quick().count(QuickLists.showsTab(TitleSort.Order.PROVIDER, emptyList(), false, null).count()))
    }

    @Test
    fun theLastLengthsAreAGuessWhenTheAppOpensAgain() = runBlocking {
        db.items().insertAll((1L..300L).map { item(it, 0, "Channel $it") })
        val file = File(context.cacheDir, "list-counts-test").apply { delete() }
        val before = ListCounts(db, scope, file)
        before.record(live(), 300, before.stamp(live()))
        withTimeout(10_000) { while (!file.exists()) delay(10) }

        val after = ListCounts(db, scope, file)
        val first = QuickPagingSource(db, after, live()).page()

        assertNull(after.exact(live()), "a guess until it is counted again")
        assertEquals(300 - PAGE.toInt(), first.itemsAfter, "the whole length from the first page")
        file.delete()
    }

    @Test
    fun aFailureIsTheListsErrorNotACrash() = runTest {
        val broken =
            QuickQuery<ChannelWithNow>(
                name = "broken",
                sql = "SELECT * FROM no_such_table",
                orderBy = "",
                args = emptyMap(),
                tables = arrayOf("items"),
            ) { dao, q -> dao.channels(q) }

        val result = QuickPagingSource(db, counts, broken).load(refresh())

        assertTrue(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun namedArgumentsBindLikeRoom() {
        val (sql, values) =
            SqlArgs.bind(
                "SELECT 'g:0:' || x, ':notAnArg', 'it''s :x' WHERE a IN (:list) AND b = :flag AND c = :flag AND d IN (:none)",
                mapOf("list" to listOf("a", "b"), "flag" to true, "none" to emptyList<String>()),
            )

        assertEquals("SELECT 'g:0:' || x, ':notAnArg', 'it''s :x' WHERE a IN (?,?) AND b = ? AND c = ? AND d IN ()", sql)
        assertEquals(listOf<Any?>("a", "b", 1L, 1L), values.toList())
        assertFailsWith<IllegalArgumentException> { SqlArgs.bind("SELECT :missing", emptyMap()) }
    }

    private fun movies(order: TitleSort.Order) = QuickLists.titles(order, 1, emptyList(), false, null, false, "", "", 0)

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
        sortTitle = name.lowercase(),
        year = 2000 + (id % 7).toInt(),
        added = id * 3 % 11,
    )

    private companion object {
        const val PAGE = 120L
    }
}
