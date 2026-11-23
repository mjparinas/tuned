package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.KidsContent
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each profile keeps its own lists, kids profiles see kids categories only, and the upgrade keeps what was there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfilesDbTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun eachProfileHasItsOwnFavouritesHistoryAndWatchLater() = runTest {
        db.items().insertAll(listOf(item(1, "News", "CBC News"), item(2, "News", "CTV News")))
        db.library().addFavourite(FavouriteEntity(1, addedAt = 100))
        db.library().saveProgress(HistoryEntity(2, positionMs = 60_000, durationMs = 600_000, watchedAt = 5))
        db.watchLater().add(WatchLaterEntity("m:9", 9, null, "Film", null, addedAt = 1))

        db.profiles().setCurrent(CurrentProfileEntity(profileId = 2))
        assertEquals(emptyList(), db.library().favouriteIds().first())
        assertNull(db.library().progress(2))
        assertEquals(emptyList(), db.watchLater().keys().first())
        db.library().addFavourite(FavouriteEntity(2, addedAt = 200))
        assertEquals(listOf(2L), db.library().favouriteIds().first())

        db.profiles().setCurrent(CurrentProfileEntity(profileId = Profiles.FIRST))
        assertEquals(listOf(1L), db.library().favouriteIds().first())
        assertEquals(60_000L, db.library().progress(2)?.positionMs)
        assertEquals(listOf("m:9"), db.watchLater().keys().first())

        db.profiles().delete(2)
        db.profiles().setCurrent(CurrentProfileEntity(profileId = 2))
        assertEquals(emptyList(), db.library().favouriteIds().first())
    }

    @Test
    fun kidsProfilesSeeKidsCategoriesOnly() = runTest {
        val items =
            listOf(
                item(1, "US| KIDS", "Treehouse TV"),
                item(2, "US | Entertainment", "US: Cartoon Network TV"),
                item(3, "US | Entertainment", "US: Family Guy TV"),
                item(4, "Sports", "TSN TV"),
            )
        db.items().insertAll(items)
        val kids = listOf(KidsContent.HIDDEN_KEY)

        suspend fun search(hidden: List<String>) =
            db.items().searchChannels("tv", Search.match("tv"), 0, hidden, adult = false, limit = 10).map { it.id }

        val shown = search(kids)
        assertEquals(listOf(1L, 2L), shown)
        // The SQL and the Kotlin rule agree.
        assertEquals(items.filter { KidsContent.isKids(it.groupName, it.name, live = true) }.map { it.id }, shown)
        assertEquals(listOf(1L, 2L, 3L, 4L), search(emptyList()))
        // A grown-up group shows only for the kids channels in it.
        assertEquals(
            listOf("US| KIDS" to 1, "US | Entertainment" to 1),
            db
                .items()
                .groups(0, kids, adult = false)
                .first()
                .map { it.groupName to it.count },
        )
    }

    @Test
    fun upgradeGivesEverythingToTheFirstProfileInTheShapeRoomExpects() {
        val helper =
            openHelper(12) { old ->
                old.execSQL(
                    "CREATE TABLE `favourites` (`itemId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`itemId`))",
                )
                old.execSQL(
                    "CREATE TABLE `history` (`itemId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`watchedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                old.execSQL("CREATE INDEX `index_history_watchedAt` ON `history` (`watchedAt`)")
                old.execSQL(
                    "CREATE TABLE `watch_later` (`key` TEXT NOT NULL, `itemId` INTEGER, `showKey` TEXT, `title` TEXT NOT NULL, " +
                        "`image` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
                old.execSQL("CREATE TABLE `seen_episodes` (`itemId` INTEGER NOT NULL, `showKey` TEXT NOT NULL, PRIMARY KEY(`itemId`))")
                old.execSQL("CREATE INDEX `index_seen_episodes_showKey` ON `seen_episodes` (`showKey`)")
            }
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO favourites VALUES (7, 500, 3)")
        old.execSQL("INSERT INTO history VALUES (8, 1000, 2000, 50)")
        old.execSQL("INSERT INTO watch_later VALUES ('s:abc', NULL, 'abc', 'Show', NULL, 9)")
        old.execSQL("INSERT INTO seen_episodes VALUES (11, 'abc')")

        TunedDatabase.MIGRATION_12_13.migrate(old)
        // 13 to 16 leave these tables alone; 17 adds the avatar.
        TunedDatabase.MIGRATION_16_17.migrate(old)
        // Speculative warm indexes on favourites and history (Room 24).
        TunedDatabase.MIGRATION_23_24.migrate(old)

        assertEquals(listOf("7|500|3|1"), rows(old, "SELECT itemId, addedAt, position, profileId FROM favourites"))
        assertEquals(listOf("8|1000|2000|50|1"), rows(old, "SELECT itemId, positionMs, durationMs, watchedAt, profileId FROM history"))
        assertEquals(listOf("s:abc|1"), rows(old, "SELECT `key`, profileId FROM watch_later"))
        assertEquals(listOf("11|abc|1"), rows(old, "SELECT itemId, showKey, profileId FROM seen_episodes"))
        assertEquals(listOf("1|0|null"), rows(old, "SELECT id, kids, avatar FROM profiles"))
        assertEquals(listOf("1"), rows(old, "SELECT profileId FROM current_profile"))

        // Same columns, keys and indices as a database Room makes from scratch, so Room's check on opening passes.
        val fresh = db.openHelper.writableDatabase
        for (table in listOf("favourites", "history", "watch_later", "seen_episodes", "profiles", "current_profile")) {
            assertEquals(shape(fresh, table), shape(old, table), table)
        }
        helper.close()
    }

    @Test
    fun avatarsAreSavedAndTheUpgradeKeepsEveryProfileOnItsLetter() = runTest {
        val id = db.profiles().insert(ProfileEntity(name = "Deen", colour = 1, kids = true, pinHash = null, createdAt = 1, avatar = "fox"))
        assertEquals("fox", db.profiles().get(id)?.avatar)

        val helper =
            openHelper(16) { old ->
                old.execSQL(
                    "CREATE TABLE `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`colour` INTEGER NOT NULL, `kids` INTEGER NOT NULL, `pinHash` TEXT, `createdAt` INTEGER NOT NULL)",
                )
            }
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO profiles VALUES (1, 'Melvin', 0, 0, NULL, 5)")

        TunedDatabase.MIGRATION_16_17.migrate(old)

        assertEquals(listOf("1|Melvin|null"), rows(old, "SELECT id, name, avatar FROM profiles"))
        assertEquals(shape(db.openHelper.writableDatabase, "profiles"), shape(old, "profiles"))
        helper.close()
    }

    private fun openHelper(
        version: Int,
        create: (SupportSQLiteDatabase) -> Unit,
    ) = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = create(db)

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            ).build(),
    )

    private fun rows(
        db: SupportSQLiteDatabase,
        sql: String,
    ): List<String> = db.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    /** Columns (name, type, not null, default, key position) and index names, in a stable order. */
    private fun shape(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<String> {
        val columns = rows(db, "SELECT name, type, `notnull`, dflt_value, pk FROM pragma_table_info('$table')").sorted()
        val indices = rows(db, "SELECT name FROM pragma_index_list('$table') WHERE origin = 'c'").sorted()
        return columns + indices
    }

    private fun item(
        id: Long,
        group: String,
        name: String,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = group,
        region = null,
        adult = false,
        logo = null,
        url = "http://example.invalid/$id",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
    )
}
