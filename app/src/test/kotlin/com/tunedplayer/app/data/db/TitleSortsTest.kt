package com.tunedplayer.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.core.TitleSort.Order
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Movies and Shows sorted by newest, year, rating or title (Melvin, 2026-11-20: "sort by newest first or ... rating"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitleSortsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    private suspend fun movies(order: Order) = db.sortedItems(order, 1, emptyList(), false, null, false, "", "", 0).all().map { it.name }

    @Test
    fun moviesSortEachWayWithUnknownsLastAndTiesInPlaylistOrder() = runTest {
        db.items().insertAll(
            listOf(
                film(1, "EN - Zulu Dawn (1979)", added = 300, rating = 6.0),
                film(2, "Avatar", added = 100, rating = null),
                film(3, "|FR| Élan", added = 200, rating = 8.5, year = 2021),
                film(4, "Mondo", added = 0, rating = 6.0),
            ),
        )
        assertEquals(listOf("EN - Zulu Dawn (1979)", "Avatar", "|FR| Élan", "Mondo"), movies(Order.PROVIDER))
        assertEquals(listOf("EN - Zulu Dawn (1979)", "|FR| Élan", "Avatar", "Mondo"), movies(Order.ADDED))
        assertEquals(listOf("|FR| Élan", "EN - Zulu Dawn (1979)", "Avatar", "Mondo"), movies(Order.YEAR))
        // Two at 6.0 keep the playlist's order; the one without a rating goes last.
        assertEquals(listOf("|FR| Élan", "EN - Zulu Dawn (1979)", "Mondo", "Avatar"), movies(Order.RATING))
        // A to Z ignores "EN - " and "|FR| " and the accent.
        assertEquals(listOf("Avatar", "|FR| Élan", "Mondo", "EN - Zulu Dawn (1979)"), movies(Order.TITLE))
    }

    @Test
    fun sortingStillHonoursTheSearch() = runTest {
        db.items().insertAll(
            listOf(film(1, "Dune", added = 1, rating = 7.0), film(2, "Dune Part Two", added = 2, rating = 8.0), film(3, "Heat")),
        )
        val found = db.sortedItems(Order.RATING, 1, emptyList(), false, null, false, "dune", Search.match("dune"), 0).all()
        assertEquals(listOf("Dune Part Two", "Dune"), found.map { it.name })
    }

    @Test
    fun everySortReadsDownAnIndexInsteadOfSortingTheList() {
        val orders =
            listOf("i.added DESC, i.sortOrder", "i.year DESC, i.sortOrder", "i.rating DESC, i.sortOrder", "i.sortTitle, i.sortOrder")
        for (order in orders) {
            val plan =
                db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM items i WHERE i.kind = 1 ORDER BY $order").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString(" / ")
                }
            assertTrue("index_items_kind_" in plan, "$order: $plan")
            assertFalse("TEMP B-TREE" in plan, "$order: $plan")
        }
    }

    @Test
    fun showsSortByTheProvidersDetailsAheadOfTheirEpisodes() = runTest {
        db.items().insertAll(
            listOf(
                episode(1, "Harbour Town", added = 500),
                episode(2, "Snowline", added = 100),
                episode(3, "The Night Desk", added = 300),
            ),
        )
        db.shows().replaceDetails(
            listOf(
                ShowDetailsEntity("snowline", added = 900, year = 2024, rating = 9.0),
                ShowDetailsEntity("harbour town", added = 0, year = 2001, rating = 7.0),
            ),
        )
        ShowGroups.rebuild(db)

        fun tab(order: Order) = db.sortedShowsTab(order, emptyList(), false, null)
        assertEquals(listOf("Snowline", "Harbour Town", "The Night Desk"), tab(Order.ADDED).all().map { it.showName })
        assertEquals(listOf("Snowline", "Harbour Town", "The Night Desk"), tab(Order.RATING).all().map { it.showName })
        assertEquals(listOf("Harbour Town", "Snowline", "The Night Desk"), tab(Order.TITLE).all().map { it.showName })
        // A search reads the episodes, and sorts by the same details.
        val searched = db.sortedShows(Order.RATING, emptyList(), false, null, false, "", "").all()
        assertEquals(listOf("Snowline", "Harbour Town", "The Night Desk"), searched.map { it.showName })
    }

    @Test
    fun aRefreshKeepsKnownTimesAndRatingsAndStampsNewTitles() = runTest {
        db.items().insertAll(listOf(film(1, "Kept", added = 1_700_000_000, rating = 7.5)))
        val writer = PlaylistWriter(db)
        writer.begin()
        // The M3U has no times or ratings: the cache's stay, and a film it didn't have is stamped as new now.
        val next = listOf(film(1, "Kept"), film(2, "New"))
        next.forEach { writer.accept(it.id) }
        writer.add(next)
        writer.commit()
        assertEquals(1_700_000_000, db.items().get(1)?.added)
        assertEquals(7.5, db.items().get(1)?.rating)
        assertTrue((db.items().get(2)?.added ?: 0) > 1_700_000_000)
        assertEquals(listOf("New", "Kept"), movies(Order.ADDED))
    }

    @Test
    fun anOldCacheGetsItsTitlesAndYearsInTheBackground() = runTest {
        db.items().insertAll(listOf(film(1, "EN - Dune (2021)").copy(sortTitle = "", year = null)))
        SortFields.fillIfMissing(db)
        assertEquals("dune", db.items().get(1)?.sortTitle)
        assertEquals(2021, db.items().get(1)?.year)
    }

    @Test
    fun theUpgradeMakesTheSameTablesAsAFreshInstall() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(20) {
                            override fun onCreate(db: SupportSQLiteDatabase) = Unit

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val old = helper.writableDatabase
        // The two tables as version 20 made them.
        old.execSQL(
            "CREATE TABLE `items` (`id` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`searchName` TEXT NOT NULL, `groupName` TEXT NOT NULL, `region` TEXT, `adult` INTEGER NOT NULL, `logo` TEXT, " +
                "`url` TEXT NOT NULL, `tvgId` TEXT, `showKey` TEXT, `showName` TEXT, `season` INTEGER, `episode` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, `number` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        )
        old.execSQL(
            "CREATE TABLE `show_groups` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `showKey` TEXT NOT NULL, " +
                "`groupName` TEXT NOT NULL, `region` TEXT, `adult` INTEGER NOT NULL, `showName` TEXT NOT NULL, `logo` TEXT, " +
                "`episodes` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)",
        )
        TunedDatabase.MIGRATION_20_21.migrate(old)
        val fresh = db.openHelper.writableDatabase
        for (table in listOf("items", "show_groups", "show_details")) assertEquals(columns(fresh, table), columns(old, table), table)
        for (index in listOf("added", "year", "rating", "sortTitle")) {
            val name = "index_items_kind_${index}_sortOrder"
            assertEquals(indexShape(fresh, name), indexShape(old, name), name)
        }
        helper.close()
    }

    private fun columns(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<String> = db.query("SELECT name, type, `notnull`, dflt_value, pk FROM pragma_table_info('$table')").use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }) }
    }

    private fun indexShape(
        db: SupportSQLiteDatabase,
        index: String,
    ): List<String> = db.query("SELECT name, `desc` FROM pragma_index_xinfo('$index') WHERE key = 1").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) + "|" + c.getString(1)) }
    }

    private fun film(
        id: Long,
        name: String,
        added: Long = 0,
        rating: Double? = null,
        year: Int? = null,
    ) = ItemEntity(
        id = id,
        kind = 1,
        name = name,
        searchName = Search.normalize(name),
        groupName = "Films",
        region = null,
        adult = false,
        logo = null,
        url = "http://example.invalid/movie/u/p/$id.mp4",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
        added = added,
        year = year ?: TitleSort.yearInName(name),
        rating = rating,
        sortTitle = TitleSort.title(name),
    )

    private fun episode(
        id: Long,
        show: String,
        added: Long,
    ) = film(id, "$show S01 E01", added = added).copy(
        kind = 2,
        showKey = Search.normalize(show),
        showName = show,
        season = 1,
        episode = 1,
        sortTitle = TitleSort.title(show),
        year = null,
    )
}
