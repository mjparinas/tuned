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
        tmdb("movie|EN - Zulu Dawn (1979)", 6.0)
        tmdb("movie||FR| Élan", 8.5)
        tmdb("movie|Mondo", 6.0)
        TitleRatingsSql.applyAll(db)
        assertEquals(listOf("EN - Zulu Dawn (1979)", "Avatar", "|FR| Élan", "Mondo"), movies(Order.PROVIDER))
        assertEquals(listOf("EN - Zulu Dawn (1979)", "|FR| Élan", "Avatar", "Mondo"), movies(Order.ADDED))
        assertEquals(listOf("|FR| Élan", "EN - Zulu Dawn (1979)", "Avatar", "Mondo"), movies(Order.YEAR))
        // Top rated is TMDB only: two at 6.0 keep the playlist's order; no TMDB rating goes last.
        assertEquals(listOf("|FR| Élan", "EN - Zulu Dawn (1979)", "Mondo", "Avatar"), movies(Order.RATING))
        // A to Z ignores "EN - " and "|FR| " and the accent.
        assertEquals(listOf("Avatar", "|FR| Élan", "Mondo", "EN - Zulu Dawn (1979)"), movies(Order.TITLE))
    }

    @Test
    fun sortingStillHonoursTheSearch() = runTest {
        db.items().insertAll(
            listOf(film(1, "Dune", added = 1, rating = 7.0), film(2, "Dune Part Two", added = 2, rating = 8.0), film(3, "Heat")),
        )
        tmdb("movie|Dune", 7.0)
        tmdb("movie|Dune Part Two", 8.0)
        TitleRatingsSql.applyAll(db)
        val found = db.sortedItems(Order.RATING, 1, emptyList(), false, null, false, "dune", Search.match("dune"), 0).all()
        assertEquals(listOf("Dune Part Two", "Dune"), found.map { it.name })
    }

    @Test
    fun everySortReadsDownAnIndexInsteadOfSortingTheList() {
        val orders =
            listOf("i.added DESC, i.sortOrder", "i.year DESC, i.sortOrder", "i.topRating DESC, i.sortOrder", "i.sortTitle, i.sortOrder")
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
        // Top rated ignores the provider ratings above; TMDB decides.
        tmdb("tv|Snowline", 9.0)
        tmdb("tv|Harbour Town", 7.0)
        ShowGroups.rebuild(db)

        fun tab(order: Order) = db.sortedShowsTab(order, emptyList(), false, null)
        assertEquals(listOf("Snowline", "Harbour Town", "The Night Desk"), tab(Order.ADDED).all().map { it.showName })
        assertEquals(listOf("Snowline", "Harbour Town", "The Night Desk"), tab(Order.RATING).all().map { it.showName })
        assertEquals(listOf("Harbour Town", "Snowline", "The Night Desk"), tab(Order.TITLE).all().map { it.showName })
        // A search reads the episodes, and sorts by the same TMDB ratings.
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

    /**
     * Melvin, 2026-11-21: "sorting by rating ... doesn't really seem to be doing anything"; 2026-11-23: "i dont care
     * about the provider provided information, i only care about tmdb". Top rated uses TMDB only; a provider rating
     * alone does not lift a title, and titles without a TMDB rating sort last.
     */
    @Test
    fun topRatedSortsOnTmdbOnlyWithUnratedLast() = runTest {
        db.items().insertAll(
            listOf(
                film(1, "Outcome (2026)"),
                film(2, "Junk (2026)"),
                film(3, "Tiny Fugitives (2026)"),
                film(4, "Livestream From Hell (2026)"),
                film(5, "Never Looked Up"),
                film(6, "The Love Hypothesis (2026)", rating = 3.0),
                film(7, "Provider Only", rating = 6.5),
            ),
        )
        tmdb("movie|Outcome (2026)", 5.4)
        tmdb("movie|Junk (2026)", null)
        tmdb("movie|Tiny Fugitives (2026)", 5.8)
        tmdb("movie|Livestream From Hell (2026)", 4.5)
        // Provider's 3.0 is ignored; TMDB's 7.9 decides.
        tmdb("movie|The Love Hypothesis (2026)", 7.9)
        TitleRatingsSql.applyAll(db)
        assertEquals(
            listOf(
                "The Love Hypothesis (2026)",
                "Tiny Fugitives (2026)",
                "Outcome (2026)",
                "Livestream From Hell (2026)",
                // No TMDB rating (including provider-only): last, in playlist order.
                "Junk (2026)",
                "Never Looked Up",
                "Provider Only",
            ),
            movies(Order.RATING),
        )
    }

    @Test
    fun aRefreshKeepsTmdbRatingsInPlace() = runTest {
        db.items().insertAll(listOf(film(1, "Low", rating = 9.0), film(2, "High", rating = 2.0)))
        tmdb("movie|Low", 3.0)
        tmdb("movie|High", 8.0)
        TitleRatingsSql.applyAll(db)
        val writer = PlaylistWriter(db)
        writer.begin()
        // The provider's ratings change, so both rows are rewritten; TMDB's ratings still decide.
        val next = listOf(film(1, "Low", rating = 9.5), film(2, "High", rating = 1.0))
        next.forEach { writer.accept(it.id) }
        writer.add(next)
        writer.commit()
        assertEquals(listOf("High", "Low"), movies(Order.RATING))
        assertEquals(8.0, db.items().get(2)?.topRating)
    }

    @Test
    fun showsSortOnTmdbRatingsOnly() = runTest {
        db.items().insertAll(
            listOf(
                episode(1, "Harbour Town", added = 1),
                episode(2, "Snowline", added = 2),
                episode(3, "The Night Desk", added = 3),
                episode(4, "Provider Only", added = 4),
            ),
        )
        // Provider 9.0 would put Harbour Town first; TMDB 6.1 keeps it second. Provider Only has no TMDB.
        db.shows().replaceDetails(
            listOf(
                ShowDetailsEntity("harbour town", added = 0, year = null, rating = 9.0),
                ShowDetailsEntity("provider only", added = 0, year = null, rating = 9.5),
            ),
        )
        tmdb("tv|Harbour Town", 6.1)
        tmdb("tv|The Night Desk", 8.4)
        ShowGroups.rebuild(db)
        val tab = db.sortedShowsTab(Order.RATING, emptyList(), false, null).all().map { it.showName }
        assertEquals(listOf("The Night Desk", "Harbour Town", "Snowline", "Provider Only"), tab)
        val searched = db.sortedShows(Order.RATING, emptyList(), false, null, false, "", "").all().map { it.showName }
        assertEquals(listOf("The Night Desk", "Harbour Town", "Snowline", "Provider Only"), searched)
    }

    /**
     * Melvin, 2026-11-23: "without a rating need to go all the way to the bottom" / "sorted in desc order, with the
     * highest scores first". A stored 0 counts as none, same as the poster showing no star.
     */
    @Test
    fun topRatedPutsZeroAndMissingRatingsLastHighestFirst() = runTest {
        db.items().insertAll(
            listOf(
                film(1, "Unrated First"),
                film(2, "Low", rating = 4.2),
                film(3, "Zero Provider", rating = 0.0),
                film(4, "High", rating = 9.1),
                film(5, "Unrated Last"),
            ),
        )
        tmdb("movie|Low", 4.2)
        tmdb("movie|High", 9.1)
        // 0 from TMDB is none, as on the poster when too few have voted or the score is empty.
        tmdb("movie|Zero Provider", 0.0)
        TitleRatingsSql.applyAll(db)
        assertEquals(null, db.items().get(3)?.topRating)
        assertEquals(listOf("High", "Low", "Unrated First", "Zero Provider", "Unrated Last"), movies(Order.RATING))
    }

    @Test
    fun showsTopRatedPutsUnratedLastHighestFirst() = runTest {
        db.items().insertAll(
            listOf(
                episode(1, "No Score", added = 1),
                episode(2, "Okay", added = 2),
                episode(3, "Great", added = 3),
                episode(4, "Also None", added = 4),
            ),
        )
        db.shows().replaceDetails(
            listOf(
                ShowDetailsEntity("okay", added = 0, year = null, rating = 5.5),
                ShowDetailsEntity("great", added = 0, year = null, rating = 8.8),
            ),
        )
        tmdb("tv|Okay", 5.5)
        tmdb("tv|Great", 8.8)
        ShowGroups.rebuild(db)
        assertEquals(
            listOf("Great", "Okay", "No Score", "Also None"),
            db.sortedShowsTab(Order.RATING, emptyList(), false, null).all().map {
                it.showName
            },
        )
        assertEquals(
            listOf("Great", "Okay", "No Score", "Also None"),
            db.sortedShows(Order.RATING, emptyList(), false, null, false, "", "").all().map { it.showName },
        )
    }

    /**
     * Melvin, 2026-11-23: provider ratings must not lift titles Top rated; only TMDB scores count, so "not found"
     * and "not asked yet" both sort last with the other unrated titles.
     */
    @Test
    fun topRatedIgnoresProviderRatingOnceTmdbSaidNotFound() = runTest {
        db.items().insertAll(
            listOf(
                film(1, "Unknown To Tmdb", rating = 9.9),
                film(2, "Known", rating = 2.0),
                film(3, "Still Waiting", rating = 7.0),
            ),
        )
        tmdb("movie|Unknown To Tmdb", rating = null, found = false)
        tmdb("movie|Known", 8.2)
        TitleRatingsSql.applyAll(db)
        assertEquals(null, db.items().get(1)?.topRating)
        assertEquals(listOf("Known", "Unknown To Tmdb", "Still Waiting"), movies(Order.RATING))
    }

    @Test
    fun showsTopRatedIgnoresProviderRatingOnceTmdbSaidNotFound() = runTest {
        db.items().insertAll(
            listOf(
                episode(1, "Provider Hot", added = 1),
                episode(2, "On Tmdb", added = 2),
                episode(3, "Not Asked Yet", added = 3),
            ),
        )
        db.shows().replaceDetails(
            listOf(
                ShowDetailsEntity("provider hot", added = 0, year = null, rating = 9.5),
                ShowDetailsEntity("not asked yet", added = 0, year = null, rating = 6.0),
            ),
        )
        tmdb("tv|Provider Hot", rating = null, found = false)
        tmdb("tv|On Tmdb", 7.4)
        ShowGroups.rebuild(db)
        assertEquals(
            listOf("On Tmdb", "Provider Hot", "Not Asked Yet"),
            db.sortedShowsTab(Order.RATING, emptyList(), false, null).all().map { it.showName },
        )
        assertEquals(
            listOf("On Tmdb", "Provider Hot", "Not Asked Yet"),
            db.sortedShows(Order.RATING, emptyList(), false, null, false, "", "").all().map { it.showName },
        )
    }

    private suspend fun tmdb(
        key: String,
        rating: Double?,
        found: Boolean = true,
    ) = db.metadata().upsert(
        MetadataEntity(
            key,
            found,
            false,
            if (found) 1 else 0,
            key.substringAfter('|'),
            null,
            null,
            rating,
            null,
            null,
            "",
            null,
            "",
            null,
            0,
        ),
    )

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
        // The two tables as version 21 made them (unchanged since 20).
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
        TunedDatabase.MIGRATION_21_22.migrate(old)
        TunedDatabase.MIGRATION_22_23.migrate(old)
        // Version 25 adds the tags column to items (version 24 only adds indexes to other tables).
        TunedDatabase.MIGRATION_24_25.migrate(old)
        val fresh = db.openHelper.writableDatabase
        for (table in listOf("items", "show_groups", "show_details")) assertEquals(columns(fresh, table), columns(old, table), table)
        for (index in listOf("added", "year", "topRating", "sortTitle")) {
            val name = "index_items_kind_${index}_sortOrder"
            assertEquals(indexShape(fresh, name), indexShape(old, name), name)
            assertTrue(indexShape(old, name).isNotEmpty(), name)
        }
        // The provider-only rating index went with version 23.
        assertTrue(indexShape(old, "index_items_kind_rating_sortOrder").isEmpty())
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
